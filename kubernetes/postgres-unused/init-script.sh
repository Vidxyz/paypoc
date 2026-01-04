#!/bin/bash
set -e

function create_user_and_database() {
	local database=$1
	local user=$2
	local password=$3
	echo "Creating user '$user' and database '$database'"
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
	    CREATE USER $user WITH PASSWORD '$password';
	    CREATE DATABASE $database;
	    GRANT ALL PRIVILEGES ON DATABASE $database TO $user;
	    \c $database
	    GRANT ALL ON SCHEMA public TO $user;
EOSQL
}

if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
	echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"
	for db in $(echo $POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
		case $db in
			ledger_db)
				create_user_and_database ledger_db ledger_user ledger_password
				;;
			payments_db)
				create_user_and_database payments_db postgres postgres
				;;
		esac
	done
	echo "Multiple databases created"
fi

