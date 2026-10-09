-- =====================================================================
-- PhonePilote — schéma initial. Idempotent : rejoué à chaque déploiement par le workflow « Backend ».
-- =====================================================================

-- ---------- Téléphones protégés ----------
-- Une ligne par installation de l'app (id généré par le téléphone).
create table if not exists public.devices (
  id               uuid primary key,
  owner_id         uuid not null default auth.uid() references auth.users(id) on delete cascade,
  manufacturer     text not null default '',
  model            text not null default '',
  android_sdk      int  not null default 0,
  app_version      int  not null default 0,
  fcm_token        text,                     -- jeton push pour envoyer les commandes
  admin_active     boolean not null default false,
  location_ok      boolean not null default false,
  battery_ok       boolean not null default false,
  owner_name       text,                     -- affiché sur l'écran verrouillé
  emergency_phone  text,                     -- numéro de secours (format 229XXXXXXXXXX)
  imei             text check (imei is null or imei ~ '^[0-9]{15}$'),
  last_seen        timestamptz not null default now(),
  created_at       timestamptz not null default now()
);
create index if not exists devices_owner_idx on public.devices(owner_id);
create index if not exists devices_imei_idx on public.devices(imei);

-- ---------- Commandes à distance (historique + suivi) ----------
create table if not exists public.commands (
  id          uuid primary key default gen_random_uuid(),
  device_id   uuid not null references public.devices(id) on delete cascade,
  kind        text not null check (kind in ('ping', 'locate', 'lock', 'unlock')),
  status      text not null default 'pending' check (status in ('pending', 'sent', 'done', 'failed')),
  payload     jsonb not null default '{}'::jsonb,
  result      jsonb,
  created_by  uuid default auth.uid() references auth.users(id) on delete set null,
  created_at  timestamptz not null default now(),
  done_at     timestamptz
);
create index if not exists commands_device_idx on public.commands(device_id, created_at desc);

-- ---------- Positions remontées par les téléphones ----------
create table if not exists public.locations (
  id          bigint generated always as identity primary key,
  device_id   uuid not null references public.devices(id) on delete cascade,
  lat         double precision not null,
  lng         double precision not null,
  accuracy_m  real,
  created_at  timestamptz not null default now()
);
create index if not exists locations_device_idx on public.locations(device_id, created_at desc);

-- ---------- Versions de l'APK (mise à jour forcée, comme xyd) ----------
create table if not exists public.app_versions (
  version_code     int primary key,
  version_name     text not null default '',
  min_version_code int  not null default 0,
  apk_url          text not null,
  changelog        text not null default '',
  created_at       timestamptz not null default now()
);

-- ---------- Sécurité (RLS) : chacun ne voit que ses téléphones ----------
alter table public.devices      enable row level security;
alter table public.commands     enable row level security;
alter table public.locations    enable row level security;
alter table public.app_versions enable row level security;

drop policy if exists "devices owner" on public.devices;
create policy "devices owner" on public.devices for all to authenticated
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists "commands owner read" on public.commands;
create policy "commands owner read" on public.commands for select to authenticated
  using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

drop policy if exists "locations owner" on public.locations;
create policy "locations owner" on public.locations for all to authenticated
  using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()))
  with check (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

-- Les commandes sont créées uniquement par l'Edge Function (service_role), jamais directement.

drop policy if exists "versions read" on public.app_versions;
create policy "versions read" on public.app_versions for select to anon, authenticated using (true);

