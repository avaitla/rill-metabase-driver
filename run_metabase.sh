#!/bin/bash
# if nobody manually set a host to listen on then go with all available interfaces and host names
if [ -z "$MB_JETTY_HOST" ]; then
    export MB_JETTY_HOST=0.0.0.0
fi

# Setup Java Options
JAVA_OPTS="${JAVA_OPTS} -XX:+IgnoreUnrecognizedVMOptions"
JAVA_OPTS="${JAVA_OPTS} -Dfile.encoding=UTF-8"
JAVA_OPTS="${JAVA_OPTS} -Dlogfile.path=target/log"
JAVA_OPTS="${JAVA_OPTS} -XX:+CrashOnOutOfMemoryError"
JAVA_OPTS="${JAVA_OPTS} -server"
JAVA_OPTS="${JAVA_OPTS} --add-opens java.base/java.nio=ALL-UNNAMED"

if [ ! -z "$JAVA_TIMEZONE" ]; then
    JAVA_OPTS="${JAVA_OPTS} -Duser.timezone=${JAVA_TIMEZONE}"
fi

# usage: file_env VAR [DEFAULT]
#    ie: file_env 'XYZ_DB_PASSWORD' 'example'
# (will allow for "$XYZ_DB_PASSWORD_FILE" to fill in the value of
#  "$XYZ_DB_PASSWORD" from a file, especially for Docker's secrets feature)
file_env() {
    local var="$1"
    local fileVar="${var}_FILE"
    local def="${2:-}"
    if [ "${!var:-}" ] && [ "${!fileVar:-}" ]; then
        echo >&2 "error: both $var and $fileVar are set (but are exclusive)"
        exit 1
    fi
    local val="$def"
    if [ "${!var:-}" ]; then
        val="${!var}"
    elif [ "${!fileVar:-}" ]; then
        val="$(< "${!fileVar}")"
    fi
    export "$var"="$val"
    unset "$fileVar"
}

# Here we define which env vars are the ones that will be supported with a "_FILE" ending
docker_setup_env() {
    file_env 'MB_DB_USER'
    file_env 'MB_DB_PASS'
    file_env 'MB_DB_CONNECTION_URI'
    file_env 'MB_EMAIL_SMTP_PASSWORD'
    file_env 'MB_EMAIL_SMTP_USERNAME'
    file_env 'MB_LDAP_PASSWORD'
    file_env 'MB_LDAP_BIND_DN'
}

# detect if the container is started as root or not
if [ $(id -u) -ne 0 ]; then
    docker_setup_env
    exec /bin/sh -c "exec java $JAVA_OPTS -jar /app/metabase.jar $@"
else
    MGID=${MGID:-2000}
    MUID=${MUID:-2000}

    getent group metabase > /dev/null 2>&1
    group_exists=$?
    if [ $group_exists -ne 0 ]; then
        addgroup --gid $MGID --system metabase
    fi

    id -u metabase > /dev/null 2>&1
    user_exists=$?
    if [[ $user_exists -ne 0 ]]; then
        adduser --disabled-password -u $MUID --ingroup metabase metabase
    fi

    db_file=${MB_DB_FILE:-/metabase.db}

    if ls $db_file\.* > /dev/null 2>&1; then
        db_exists=true
    else
        db_exists=false
    fi
    if [[ -d "$db_file" ]]; then
        db_directory=true
    else
        db_directory=false
    fi

    new_db_dir=$(dirname $db_file)/$(basename $db_file)

    if [[ $db_exists = "true" && ! $db_directory = "true" ]]; then
        mkdir $new_db_dir
        mv $db_file\.* $new_db_dir/
    fi

    if [[ $db_exists = "false" && $db_directory = "false" ]]; then
        mkdir $new_db_dir
    fi

    docker_setup_env
    export MB_DB_FILE=$new_db_dir/$(basename $db_file)

    chown metabase:metabase $new_db_dir $new_db_dir/* 2>/dev/null

    chmod o+r /app/metabase.jar

    INITIAL_DB=$(ls /app/initial*.db 2> /dev/null | head -n 1)
    if [ -f "${INITIAL_DB}" ]; then
        echo "Initializing Metabase database from H2 database ${INITIAL_DB}..."
        chmod o+r ${INITIAL_DB}
        su metabase -s /bin/sh -c "exec java $JAVA_OPTS -jar /app/metabase.jar load-from-h2 ${INITIAL_DB%.mv.db} $@"

        if [ $? -ne 0 ]; then
            echo "Failed to initialize database from H2 database!"
            exit 1
        fi

        echo "Done."
    fi

    exec su metabase -s /bin/sh -c "export JAVA_HOME=/opt/java/openjdk && export PATH=\$JAVA_HOME/bin:\$PATH && exec java $JAVA_OPTS -jar /app/metabase.jar $@"
fi
