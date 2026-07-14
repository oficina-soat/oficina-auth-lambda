CREATE SEQUENCE pessoa_seq START WITH 1;
CREATE SEQUENCE papel_seq START WITH 1;
CREATE SEQUENCE usuario_seq START WITH 1;

CREATE TABLE pessoa (
  id bigint PRIMARY KEY,
  external_id uuid UNIQUE,
  documento varchar(255) NOT NULL UNIQUE,
  tipo_pessoa varchar(20) NOT NULL,
  nome varchar(255)
);
CREATE TABLE papel (id bigint PRIMARY KEY, nome varchar(255) NOT NULL UNIQUE);
CREATE TABLE usuario (
  id bigint PRIMARY KEY,
  external_id uuid UNIQUE,
  pessoa_id bigint NOT NULL UNIQUE REFERENCES pessoa(id),
  password varchar(255),
  status varchar(255) NOT NULL,
  last_event_at timestamptz
);
CREATE TABLE usuario_papel (
  usuario_id bigint NOT NULL REFERENCES usuario(id),
  papel_id bigint NOT NULL REFERENCES papel(id),
  PRIMARY KEY (usuario_id, papel_id)
);
CREATE TABLE auth_consumed_event (
  event_id uuid PRIMARY KEY,
  aggregate_id varchar(255) NOT NULL,
  event_type varchar(100) NOT NULL,
  occurred_at timestamptz NOT NULL,
  consumed_at timestamptz NOT NULL
);
INSERT INTO papel (id, nome) VALUES
  (1, 'administrativo'), (2, 'mecanico'), (3, 'recepcionista');
