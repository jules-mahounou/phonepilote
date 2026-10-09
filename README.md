# PhonePilote

Service anti-vol et de récupération de téléphones (Bénin). Application Android **Device Admin** (Kotlin + Jetpack Compose),
plateforme **Supabase**, commandes à distance par **Firebase Cloud Messaging**, site de téléchargement sur `phonepilote.xydhub.tech`.

Tout se construit et se déploie par GitHub Actions : aucun build local nécessaire.

## Arborescence

| Dossier | Contenu |
|---|---|
| `app/` | Application Android (un écran + bottom sheets, design glassmorphisme + flat) |
| `supabase/migrations/` | Schéma SQL + RLS (rejoué à chaque déploiement, idempotent) |
| `web/` | Site de téléchargement (index.html, logo, .htaccess) + espace admin `web/admin/` |
| `.github/workflows/` | Build APK, publication FTP, backend, site, anti-pause Supabase |

## Workflows

| Workflow | Déclenchement | Rôle |
|---|---|---|
| **Android** | chaque push, ou manuel | Build de l'APK (artefact téléchargeable). Manuel avec « Publier » coché : FTP + version Supabase + Release GitHub |
| **Backend (Supabase)** | push dans `supabase/`, ou manuel | Applique les migrations SQL |
| **Site** | push dans `web/`, ou manuel | Envoie le site par FTP |
| **Supabase anti-pause** | tous les 3 jours | Évite la mise en pause du plan gratuit |

## Configuration (Settings → Secrets and variables → Actions)

**Variables** : `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_PROJECT_REF`, `FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`,
`FIREBASE_API_KEY`, `FIREBASE_SENDER_ID`, `FTP_DIR`, `SITE_URL` (facultatif), `ADMIN_PHONES` (numéros admin, séparés par des virgules).

**Secrets** : `SUPABASE_ACCESS_TOKEN`, `SUPABASE_SERVICE_ROLE_KEY`, `FTP_HOST`, `FTP_USER`, `FTP_PASSWORD`,
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Sans configuration, l'APK se construit quand même (signature debug, mode démo).

## Points techniques

- **IMEI** : Android 10+ interdit aux apps de lire l'IMEI. Il est saisi par l'utilisateur (`*#06#`), avec contrôle de la clé de Luhn.
- **Verre** : dessiné sans bibliothèque (halos en dégradés radiaux + cartes translucides), rendu identique d'Android 8 à 15.
- **Tecno / Infinix / Itel** : l'app guide vers l'écran constructeur de démarrage automatique et la désactivation de l'optimisation batterie.

## Espace admin (`/admin`)

`https://phonepilote.xydhub.tech/admin/` : consultation de la base (comptes, téléphones, commandes, positions, versions), lecture seule en mode démo.

- Connexion avec un compte PhonePilote (numéro + mot de passe, créé dans l'app) listé dans la variable `ADMIN_PHONES`,
  puis lancer le workflow **Backend (Supabase)** à la main pour appliquer la liste.
- La sécurité est côté base : RLS + `public.is_admin()`. Un compte non admin ne reçoit aucune donnée des autres.
- Page non indexée, sans cache, CSP stricte, session limitée à l'onglet, déconnexion après 30 min d'inactivité.
