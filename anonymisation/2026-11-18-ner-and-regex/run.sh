#!/usr/bin/env bash
# Construit le jar de l'API puis la lance, en HTTP et en HTTPS.
#
# Usage : ./run.sh [--tests] [--no-build] [-- options Spring Boot supplémentaires]
#   --tests       exécute aussi les tests (mvn clean verify) ; par défaut ils sont ignorés
#                 (mvn -DskipTests clean package)
#   --no-build    ne construit rien, lance le jar déjà présent
#   -h, --help    affiche cette aide
#
# Ports (variables d'environnement) : HTTP_PORT (défaut 8080), HTTPS_PORT (défaut 8443).
# Le HTTPS utilise un certificat AUTO-SIGNÉ de développement, créé au premier lancement dans
# .local/https (ignoré par git) avec un mot de passe aléatoire. Les navigateurs et curl le refusent
# par défaut : curl -k, ou le faire accepter. Pour un autre certificat PKCS12 :
#   HTTPS_KEYSTORE=/chemin/keystore.p12 HTTPS_KEYSTORE_PASSWORD=... ./run.sh
#
# Autres variables : MODEL_DIR (défaut models/camembert-ner, voir scripts/download-model.sh),
# JAVA_HOME (JDK 21 ; sinon cherché dans ~/.jdks, ~/.asdf, ~/.sdkman et /usr/lib/jvm), JAVA_OPTS.
# Sans modèle, l'application refuse de démarrer ; pour essayer sans NER :
#   ./run.sh -- --anonymisation.ner.enabled=false
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"

HTTP_PORT="${HTTP_PORT:-8080}"
HTTPS_PORT="${HTTPS_PORT:-8443}"
TLS_DIR="$ROOT/.local/https"

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

# --- Arguments ------------------------------------------------------------------------------------
run_tests=no
build=yes
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tests) run_tests=yes ;;
    --no-build) build=no ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    *) fail "option inconnue : $1 (voir --help)" ;;
  esac
  shift
done

# --- JDK 21 ---------------------------------------------------------------------------------------
java_major() {
  "$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'
}

find_jdk() {
  local candidate
  for candidate in "${JAVA_HOME:-}" "$HOME"/.jdks/*21* "$HOME"/.asdf/installs/java/*21* \
    "$HOME"/.sdkman/candidates/java/21* /usr/lib/jvm/*21*; do
    # JAVA_HOME vide : "$candidate/bin" vaudrait /bin, où un javac d'un autre JDK peut traîner.
    [[ -n "$candidate" ]] || continue
    if [[ -x "$candidate/bin/javac" && -x "$candidate/bin/keytool" && "$(java_major "$candidate")" == 21 ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

JAVA_HOME="$(find_jdk)" || fail "JDK 21 introuvable (avec javac et keytool). Définir JAVA_HOME, par exemple : export JAVA_HOME=~/.jdks/jbr-21.0.11"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "JDK : $JAVA_HOME"

# --- Ports ----------------------------------------------------------------------------------------
port_busy() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

[[ "$HTTP_PORT" != "$HTTPS_PORT" ]] || fail "HTTP_PORT et HTTPS_PORT sont identiques ($HTTP_PORT)."
for port in "$HTTP_PORT" "$HTTPS_PORT"; do
  if port_busy "$port"; then
    fail "le port $port est déjà utilisé. En choisir un autre, par exemple : HTTP_PORT=8099 HTTPS_PORT=8499 ./run.sh"
  fi
done

# --- Build ----------------------------------------------------------------------------------------
if [[ "$build" == yes ]]; then
  if [[ "$run_tests" == yes ]]; then
    mvn -B clean verify
  else
    mvn -B -DskipTests clean package
  fi
fi

jars=(anonymisation-api/target/api-*.jar)
[[ ${#jars[@]} -eq 1 && -f "${jars[0]}" ]] || fail "jar de l'API introuvable dans anonymisation-api/target (lancer sans --no-build)."
jar="${jars[0]}"

# --- Certificat HTTPS -----------------------------------------------------------------------------
if [[ -n "${HTTPS_KEYSTORE:-}" ]]; then
  keystore="$HTTPS_KEYSTORE"
  [[ -f "$keystore" ]] || fail "HTTPS_KEYSTORE introuvable : $keystore"
  [[ -n "${HTTPS_KEYSTORE_PASSWORD:-}" ]] || fail "HTTPS_KEYSTORE_PASSWORD est obligatoire avec HTTPS_KEYSTORE."
  password="$HTTPS_KEYSTORE_PASSWORD"
else
  keystore="$TLS_DIR/keystore.p12"
  password_file="$TLS_DIR/keystore.password"
  # Régénéré s'il manque un des deux fichiers ou après environ onze mois (validité : un an).
  if [[ ! -s "$keystore" || ! -s "$password_file" || -n "$(find "$keystore" -mtime +330)" ]]; then
    echo "Création d'un certificat auto-signé de développement dans $TLS_DIR"
    rm -f "$keystore" "$password_file"
    mkdir -p "$TLS_DIR"
    chmod 700 "$TLS_DIR"
    (umask 077 && head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$password_file")
    if ! keytool_output="$(keytool -genkeypair -alias anonymisation -keyalg RSA -keysize 2048 \
      -validity 365 -storetype PKCS12 -keystore "$keystore" -storepass:file "$password_file" \
      -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" 2>&1)"; then
      rm -f "$keystore" "$password_file"
      fail "keytool a échoué : $keytool_output"
    fi
    chmod 600 "$keystore"
  fi
  password="$(<"$password_file")"
fi

# --- Lancement ------------------------------------------------------------------------------------
# Le mot de passe passe par l'environnement : une option de ligne de commande serait visible dans ps.
export SERVER_SSL_KEYSTOREPASSWORD="$password"
export ANONYMISATION_MODEL_DIR="${MODEL_DIR:-$ROOT/models/camembert-ner}"

echo
echo "HTTP  : http://localhost:$HTTP_PORT"
echo "HTTPS : https://localhost:$HTTPS_PORT  (certificat auto-signé : curl -k)"
echo "Swagger UI : /swagger-ui.html sur chacun des deux ports. Ctrl-C pour arrêter."
echo

# shellcheck disable=SC2086 # JAVA_OPTS est une liste d'options séparées par des espaces
exec java ${JAVA_OPTS:-} -jar "$jar" \
  --server.port="$HTTPS_PORT" \
  --anonymisation.http.port="$HTTP_PORT" \
  --server.ssl.key-store="file:$keystore" \
  --server.ssl.key-store-type=PKCS12 \
  "$@"
