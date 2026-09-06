-- One database per service: each owns its schema outright, which is the whole point of the
-- microservice boundary. They share a server here only to keep the compose file small.
CREATE DATABASE retailbank_auth_db;
CREATE DATABASE retailbank_customer_db;
CREATE DATABASE retailbank_account_db;
CREATE DATABASE retailbank_transaction_db;
CREATE DATABASE retailbank_loan_db;
CREATE DATABASE retailbank_audit_db;
