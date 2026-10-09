-- =====================================================================
-- PhonePilote — état de protection du téléphone + historique des positions. Idempotent.
-- =====================================================================

-- ---------- Nouveaux états remontés par l'app ----------
-- screen_lock_ok   : code PIN / schéma / mot de passe actif (sans lui, « verrouiller » n'éteint que l'écran).
-- location_enabled : interrupteur « Localisation » du téléphone (différent de la permission de l'app).
-- location_off_since : posé par le trigger ci-dessous quand la localisation est coupée.
alter table public.devices add column if not exists screen_lock_ok boolean;
alter table public.devices add column if not exists location_enabled boolean not null default true;
alter table public.devices add column if not exists location_off_since timestamptz;

-- ---------- Origine d'une position ----------
alter table public.locations add column if not exists source text not null default 'command';
alter table public.locations drop constraint if exists locations_source_check;
alter table public.locations add constraint locations_source_check check (source in ('command', 'periodic'));

-- ---------- Trigger sur devices ----------
-- 1. test_mode ne peut être changé que par un admin (la policy « devices owner » laisse le propriétaire
--    écrire toute sa ligne : sans ce garde-fou, il pourrait passer son téléphone en mode test).
-- 2. location_off_since = moment où la localisation a été coupée (null quand elle est allumée).
create or replace function public.devices_guard() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    if not public.is_admin() then new.test_mode := false; end if;
    new.location_off_since := case when new.location_enabled then null else now() end;
  else
    if new.test_mode is distinct from old.test_mode and not public.is_admin() then
      new.test_mode := old.test_mode;
    end if;
    new.location_off_since := case
      when new.location_enabled then null
      else coalesce(old.location_off_since, now())
    end;
  end if;
  return new;
end $$;

drop trigger if exists devices_guard on public.devices;
create trigger devices_guard before insert or update on public.devices
  for each row execute function public.devices_guard();
