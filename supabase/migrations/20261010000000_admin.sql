-- =====================================================================
-- PhonePilote — accès administrateur (lecture seule). Idempotent.
-- La sécurité est appliquée ici, côté base (RLS) : la page /admin ne voit rien
-- si le compte connecté n'est pas dans public.admins.
-- =====================================================================

-- ---------- Liste des administrateurs ----------
-- Remplie uniquement par le workflow « Backend » (variable ADMIN_PHONES) ou en SQL :
-- aucune policy d'écriture, donc impossible de s'ajouter depuis l'API.
create table if not exists public.admins (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  created_at  timestamptz not null default now()
);
alter table public.admins enable row level security;

drop policy if exists "admins self read" on public.admins;
create policy "admins self read" on public.admins for select to authenticated
  using (user_id = auth.uid());

-- ---------- is_admin() ----------
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.admins a where a.user_id = auth.uid());
$$;
revoke all on function public.is_admin() from public, anon;
grant execute on function public.is_admin() to authenticated;

-- ---------- Lecture de toutes les données pour les admins ----------
drop policy if exists "devices admin read" on public.devices;
create policy "devices admin read" on public.devices for select to authenticated
  using (public.is_admin());

drop policy if exists "commands admin read" on public.commands;
create policy "commands admin read" on public.commands for select to authenticated
  using (public.is_admin());

drop policy if exists "locations admin read" on public.locations;
create policy "locations admin read" on public.locations for select to authenticated
  using (public.is_admin());

-- ---------- Comptes utilisateurs (auth.users n'est pas exposé par l'API) ----------
create or replace function public.admin_users()
returns table (id uuid, phone text, name text, created_at timestamptz, last_sign_in_at timestamptz, devices bigint, is_admin boolean)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then
    raise exception 'Accès refusé' using errcode = '42501';
  end if;
  return query
    select u.id,
           coalesce(nullif(u.raw_user_meta_data->>'phone', ''), split_part(u.email::text, '@', 1)),
           u.raw_user_meta_data->>'name',
           u.created_at,
           u.last_sign_in_at,
           (select count(*) from public.devices d where d.owner_id = u.id),
           exists (select 1 from public.admins a where a.user_id = u.id)
    from auth.users u
    order by u.created_at desc;
end $$;
revoke all on function public.admin_users() from public, anon;
grant execute on function public.admin_users() to authenticated;
