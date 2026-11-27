#!/usr/bin/env bash
# Installe l'API comme service systemd (HTTP et HTTPS) sur la machine courante. À lancer en root.
#
# Usage : sudo ./install-service.sh [options]
#   --jar FICHIER                jar à installer (défaut : anonymisation-api/target/api-*.jar, construit
#                                par run.sh ou par « mvn -DskipTests clean package »)
#   --model-dir DOSSIER          modèle NER à copier (défaut : models/camembert-ner du projet s'il est
#                                présent, sinon téléchargé par scripts/download-model.sh)
#   --host NOM[,NOM...]          noms DNS ou adresses IP à mettre dans le certificat auto-signé : ceux que
#                                les clients utilisent (localhost et 127.0.0.1 y sont toujours)
#   --keystore FICHIER.p12       utiliser ce certificat PKCS12 plutôt qu'en générer un auto-signé ;
#   --keystore-password-file F   fichier contenant son mot de passe (obligatoire avec --keystore)
#   --new-cert                   régénérer le certificat auto-signé même s'il existe déjà
#   --http-port N                port HTTP (défaut 80)
#   --https-port N               port HTTPS (défaut 443)
#   --java-opts "OPTIONS"        options de la JVM, par exemple "-Xmx1g"
#   --no-start                   installer sans démarrer le service
#   --uninstall                  arrête et supprime le service ; garde le modèle et le certificat
#   --purge                      comme --uninstall, et supprime aussi le modèle, le certificat et
#                                l'utilisateur système
#   -h, --help                   affiche cette aide
#
# Relancer le script met à jour le jar et la configuration (idempotent). Le service s'appelle
# « anonymisation » : journalctl -u anonymisation -f. Fichiers : /opt/anonymisation (jar, modèle),
# /etc/anonymisation (certificat, mot de passe, environnement). Il tourne sous l'utilisateur système
# « anonymisation » ; les ports inférieurs à 1024 lui sont accordés par capacité, sans passer par root.
# Il faut un JDK ou JRE 21 avec keytool (Amazon Linux : java-21-amazon-corretto-headless ; Debian et
# Ubuntu : openjdk-21-jre-headless), systemd et curl.
# L'API n'a AUCUNE authentification : restreindre l'accès réseau (groupe de sécurité, pare-feu).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

SERVICE=anonymisation
APP_DIR=/opt/anonymisation
CONF_DIR=/etc/anonymisation
UNIT_FILE="/etc/systemd/system/$SERVICE.service"
MODEL_TARGET="$APP_DIR/models/camembert-ner"
KEYSTORE="$CONF_DIR/keystore.p12"
PASSWORD_FILE="$CONF_DIR/keystore.password"
ENV_FILE="$CONF_DIR/$SERVICE.env"
PROBE_BODY='{"text": "[PERSONNE_1] habite ici."}'

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

need_value() {
  [[ $# -ge 2 ]] || fail "$1 demande une valeur (voir --help)"
}

# --- Arguments ------------------------------------------------------------------------------------
jar=""
model_dir=""
hosts=""
keystore_src=""
keystore_password_file=""
new_cert=no
http_port=80
https_port=443
java_opts=""
start=yes
action=install

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) need_value "$@"; jar="$2"; shift ;;
    --model-dir) need_value "$@"; model_dir="$2"; shift ;;
    --host) need_value "$@"; hosts="$2"; shift ;;
    --keystore) need_value "$@"; keystore_src="$2"; shift ;;
    --keystore-password-file) need_value "$@"; keystore_password_file="$2"; shift ;;
    --new-cert) new_cert=yes ;;
    --http-port) need_value "$@"; http_port="$2"; shift ;;
    --https-port) need_value "$@"; https_port="$2"; shift ;;
    --java-opts) need_value "$@"; java_opts="$2"; shift ;;
    --no-start) start=no ;;
    --uninstall) action=uninstall ;;
    --purge) action=purge ;;
    -h | --help)
      usage
      exit 0
      ;;
    *) fail "option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

[[ $EUID -eq 0 ]] || fail "à lancer en root : sudo $0"
command -v systemctl >/dev/null && [[ -d /run/systemd/system ]] || fail "systemd est requis (systemctl introuvable ou inactif)."

# --- Désinstallation ------------------------------------------------------------------------------
if [[ "$action" != install ]]; then
  systemctl disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$UNIT_FILE"
  systemctl daemon-reload
  echo "Service $SERVICE supprimé."
  if [[ "$action" == purge ]]; then
    rm -rf "$APP_DIR" "$CONF_DIR"
    if id "$SERVICE" >/dev/null 2>&1; then
      userdel "$SERVICE" || echo "Avertissement : utilisateur $SERVICE non supprimé." >&2
    fi
    echo "Modèle, certificat et utilisateur supprimés."
  fi
  exit 0
fi

# --- Contrôles ------------------------------------------------------------------------------------
for port in "$http_port" "$https_port"; do
  [[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1 && port <= 65535)) || fail "port invalide : $port"
done
[[ "$http_port" != "$https_port" ]] || fail "--http-port et --https-port sont identiques ($http_port)."
if [[ -n "$keystore_src" ]]; then
  [[ -f "$keystore_src" ]] || fail "certificat introuvable : $keystore_src"
  [[ -f "$keystore_password_file" ]] || fail "--keystore demande --keystore-password-file (fichier existant)."
fi

java_major() {
  "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'
}

find_java_home() {
  local candidates=() candidate
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi
  candidates+=(/usr/lib/jvm/*21*)
  if command -v java >/dev/null; then
    candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")")
  fi
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/keytool" && "$(java_major "$candidate/bin/java")" == 21 ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

java_home="$(find_java_home)" || fail "Java 21 (avec keytool) introuvable. Installer java-21-amazon-corretto-headless (Amazon Linux) ou openjdk-21-jre-headless (Debian, Ubuntu), ou définir JAVA_HOME."
echo "Java : $java_home"

if [[ -z "$jar" ]]; then
  for candidate in "$ROOT"/anonymisation-api/target/api-*.jar "$ROOT"/api-*.jar; do
    if [[ -f "$candidate" && ( -z "$jar" || "$candidate" -nt "$jar" ) ]]; then
      jar="$candidate"
    fi
  done
fi
[[ -n "$jar" && -f "$jar" ]] || fail "jar introuvable. Le construire (./run.sh ou mvn -DskipTests clean package) ou l'indiquer avec --jar."
echo "Jar  : $jar"

have_model() {
  [[ -s "$1/config.json" && -s "$1/tokenizer.json" && -s "$1/onnx/model.onnx" ]]
}

# --- Service arrêté, utilisateur et dossiers ------------------------------------------------------
was_active=no
if systemctl is-active --quiet "$SERVICE"; then
  was_active=yes
fi
systemctl stop "$SERVICE" 2>/dev/null || true

port_busy() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

for port in "$http_port" "$https_port"; do
  if port_busy "$port"; then
    if [[ "$was_active" == yes ]]; then
      systemctl start "$SERVICE" || true
    fi
    fail "le port $port est déjà utilisé par un autre processus. Arrêter l'ancien service ou choisir un autre port (--http-port, --https-port)."
  fi
done

if ! id "$SERVICE" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir "$APP_DIR" --no-create-home \
    --shell "$(command -v nologin || echo /bin/false)" "$SERVICE"
fi
install -d -m 755 "$APP_DIR"
install -d -m 750 -o root -g "$SERVICE" "$CONF_DIR"

# --- Jar et modèle --------------------------------------------------------------------------------
install -m 644 "$jar" "$APP_DIR/api.jar"

if [[ -n "$model_dir" ]]; then
  have_model "$model_dir" || fail "modèle incomplet dans $model_dir (config.json, tokenizer.json et onnx/model.onnx attendus)."
  model_source="$model_dir"
  if [[ "$(readlink -f "$model_dir")" == "$(readlink -f "$MODEL_TARGET")" ]]; then
    model_source=""
  fi
elif have_model "$MODEL_TARGET"; then
  echo "Modèle : déjà installé dans $MODEL_TARGET"
  model_source=""
elif have_model "$ROOT/models/camembert-ner"; then
  model_source="$ROOT/models/camembert-ner"
else
  model_source=""
  [[ -f "$ROOT/scripts/download-model.sh" ]] || fail "modèle introuvable et scripts/download-model.sh absent : indiquer --model-dir."
  echo "Modèle : téléchargement (environ 440 Mo) dans $MODEL_TARGET"
  MODEL_DIR="$MODEL_TARGET" bash "$ROOT/scripts/download-model.sh"
fi
if [[ -n "$model_source" ]]; then
  echo "Modèle : copie de $model_source"
  for file in config.json tokenizer.json onnx/model.onnx; do
    install -D -m 644 "$model_source/$file" "$MODEL_TARGET/$file"
  done
fi
have_model "$MODEL_TARGET" || fail "modèle incomplet dans $MODEL_TARGET."

# --- Certificat HTTPS -----------------------------------------------------------------------------
if [[ -n "$keystore_src" ]]; then
  echo "Certificat : $keystore_src"
  install -m 640 -o root -g "$SERVICE" "$keystore_src" "$KEYSTORE"
  (umask 077 && printf '%s' "$(<"$keystore_password_file")" >"$PASSWORD_FILE")
elif [[ "$new_cert" == no && -s "$KEYSTORE" && -s "$PASSWORD_FILE" ]]; then
  echo "Certificat : celui déjà installé est conservé (--new-cert pour le régénérer)."
  if [[ -n "$hosts" ]]; then
    echo "Avertissement : --host est ignoré tant que le certificat n'est pas régénéré (--new-cert)." >&2
  fi
else
  san="dns:localhost,ip:127.0.0.1"
  common_name=localhost
  if [[ -n "$hosts" ]]; then
    IFS=, read -ra names <<<"$hosts"
    common_name="${names[0]}"
    for name in "${names[@]}"; do
      case "$name" in
        localhost | 127.0.0.1 | "") ;;
        *:*) san+=",ip:$name" ;;
        *[!0-9.]*) san+=",dns:$name" ;;
        *) san+=",ip:$name" ;;
      esac
    done
  fi
  echo "Certificat auto-signé (valable un an) pour : $san"
  rm -f "$KEYSTORE" "$PASSWORD_FILE"
  password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  if ! keytool_output="$(PASS="$password" "$java_home/bin/keytool" -genkeypair -alias anonymisation \
    -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore "$KEYSTORE" \
    -storepass:env PASS -dname "CN=$common_name" -ext "SAN=$san" 2>&1)"; then
    rm -f "$KEYSTORE"
    fail "keytool a échoué : $keytool_output"
  fi
  chown "root:$SERVICE" "$KEYSTORE"
  chmod 640 "$KEYSTORE"
  (umask 077 && printf '%s' "$password" >"$PASSWORD_FILE")
fi

# Le mot de passe passe par un fichier d'environnement lisible par root seul : une option de ligne de
# commande serait visible dans ps. Les guillemets et antislashs y sont échappés pour systemd.
quote_env() {
  local value="${1//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}
(
  umask 077
  {
    echo "SERVER_SSL_KEYSTOREPASSWORD=$(quote_env "$(<"$PASSWORD_FILE")")"
    echo "ANONYMISATION_MODEL_DIR=$MODEL_TARGET"
  } >"$ENV_FILE"
)

# --- Unité systemd --------------------------------------------------------------------------------
capabilities=""
if ((http_port < 1024 || https_port < 1024)); then
  capabilities=$'AmbientCapabilities=CAP_NET_BIND_SERVICE\nCapabilityBoundingSet=CAP_NET_BIND_SERVICE'
fi

cat >"$UNIT_FILE" <<EOF
[Unit]
Description=POC anonymisation (API REST, HTTP et HTTPS)
After=network-online.target
Wants=network-online.target

[Service]
User=$SERVICE
Group=$SERVICE
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$java_home/bin/java ${java_opts:+$java_opts }-jar $APP_DIR/api.jar --server.port=$https_port --anonymisation.http.port=$http_port --server.ssl.key-store=file:$KEYSTORE --server.ssl.key-store-type=PKCS12
$capabilities
NoNewPrivileges=true
PrivateTmp=true
# Le chargement du modèle prend un moment ; Java quitte avec 143 sur SIGTERM.
TimeoutStartSec=180
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE"

if [[ "$start" == no ]]; then
  echo "Service installé, non démarré : systemctl start $SERVICE"
  exit 0
fi

systemctl restart "$SERVICE"

# --- Vérification ---------------------------------------------------------------------------------
probe() {
  curl --silent --insecure --output /dev/null --write-out '%{http_code}' --max-time 30 \
    --request POST --header 'Content-Type: application/json' --data "$PROBE_BODY" "$1" || true
}

if command -v curl >/dev/null; then
  echo "Démarrage (le chargement du modèle prend quelques secondes)..."
  deadline=$((SECONDS + 180))
  until [[ "$(probe "http://127.0.0.1:$http_port/api/verifications/chaine")" == 200 ]]; do
    if ! systemctl is-active --quiet "$SERVICE"; then
      journalctl -u "$SERVICE" -n 30 --no-pager || true
      fail "le service s'est arrêté (journal ci-dessus)."
    fi
    ((SECONDS < deadline)) || fail "pas de réponse HTTP après 180 s : journalctl -u $SERVICE -n 50"
    sleep 2
  done
  [[ "$(probe "https://127.0.0.1:$https_port/api/verifications/chaine")" == 200 ]] ||
    fail "le HTTP répond mais pas le HTTPS : journalctl -u $SERVICE -n 50"
else
  echo "curl absent : démarrage non vérifié. Suivre : journalctl -u $SERVICE -f"
fi

echo
echo "Service $SERVICE installé et démarré."
echo "  HTTP  : port $http_port    HTTPS : port $https_port (Swagger UI : /swagger-ui.html)"
echo "  Statut : systemctl status $SERVICE    Journal : journalctl -u $SERVICE -f"
echo "  Mise à jour : relancer ce script avec le nouveau jar."
echo "L'API n'a aucune authentification : n'ouvrir ces ports qu'aux machines autorisées."
