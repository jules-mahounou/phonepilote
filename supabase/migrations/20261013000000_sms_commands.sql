-- =====================================================================
-- PhonePilote — commandes par SMS (état remonté par l'app, pour /admin). Idempotent.
-- Le code secret n'est JAMAIS envoyé au serveur : seul le téléphone en garde l'empreinte.
-- =====================================================================
alter table public.devices add column if not exists sms_enabled boolean not null default false;
alter table public.devices add column if not exists sms_blocked_until timestamptz;   -- trop de mauvais codes
alter table public.devices add column if not exists sms_last_command text;          -- 'lock' | 'locate'
alter table public.devices add column if not exists sms_last_at timestamptz;

alter table public.locations drop constraint if exists locations_source_check;
alter table public.locations add constraint locations_source_check check (source in ('command', 'periodic', 'sms'));
