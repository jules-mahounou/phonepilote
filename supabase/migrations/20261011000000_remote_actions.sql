-- =====================================================================
-- PhonePilote — actions à distance (verrouiller / localiser), mode TEST. Idempotent.
--
-- Garde-fou démo : tant que les agréments ne sont pas accordés, une action n'est possible
-- QUE sur un téléphone explicitement marqué « test » (devices.test_mode = true).
-- La bascule se fait depuis /admin ; un utilisateur normal ne peut jamais l'activer (aucune
-- policy d'écriture sur test_mode, seul l'admin via la fonction ci-dessous).
-- =====================================================================

-- ---------- Drapeau « téléphone de test » ----------
alter table public.devices add column if not exists test_mode boolean not null default false;

-- ---------- Le propriétaire (le téléphone) peut mettre à jour ses propres commandes ----------
-- Sert à renvoyer le résultat (« done » + position/heure) après exécution.
drop policy if exists "commands owner update" on public.commands;
create policy "commands owner update" on public.commands for update to authenticated
  using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()))
  with check (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

-- ---------- Admin : activer / désactiver le mode test d'un téléphone ----------
create or replace function public.admin_set_test_mode(device uuid, enabled boolean)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then
    raise exception 'Accès refusé' using errcode = '42501';
  end if;
  update public.devices set test_mode = enabled where id = device;
end $$;
revoke all on function public.admin_set_test_mode(uuid, boolean) from public, anon;
grant execute on function public.admin_set_test_mode(uuid, boolean) to authenticated;

-- ---------- Admin : envoyer une commande à un téléphone de test ----------
-- Refuse toute action si le téléphone n'est pas en mode test (garde-fou agréments).
-- N'écrit QUE la commande ; l'envoi du push instantané (FCM) sera ajouté avec la clé
-- de compte de service. En attendant, le téléphone lit ses commandes en attente.
create or replace function public.admin_send_command(device uuid, action text)
returns uuid
language plpgsql security definer set search_path = public as $$
declare
  cmd_id uuid;
  is_test boolean;
begin
  if not public.is_admin() then
    raise exception 'Accès refusé' using errcode = '42501';
  end if;
  if action not in ('ping', 'locate', 'lock', 'unlock') then
    raise exception 'Action inconnue : %', action using errcode = '22023';
  end if;
  select test_mode into is_test from public.devices where id = device;
  if is_test is null then
    raise exception 'Téléphone introuvable' using errcode = 'P0002';
  end if;
  if not is_test then
    raise exception 'Action refusée : ce téléphone n''est pas en mode test (agréments requis).' using errcode = '42501';
  end if;
  insert into public.commands(device_id, kind, created_by)
    values (device, action, auth.uid())
    returning id into cmd_id;
  return cmd_id;
end $$;
revoke all on function public.admin_send_command(uuid, text) from public, anon;
grant execute on function public.admin_send_command(uuid, text) to authenticated;
