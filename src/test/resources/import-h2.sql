INSERT INTO pessoa (id, documento) VALUES
    (1, '84191404067'),
    (2, '36655462007'),
    (3, '17245011010');

INSERT INTO papel (id, papel) VALUES
    (1, 'administrativo'),
    (2, 'mecanico'),
    (3, 'recepcionista');

INSERT INTO usuario (id, pessoa_id, password, status) VALUES
    (1, 1, '$2a$12$1CBAHD.wKOCpNFGnEMUfn.sMSf8Muag0NWrtrBBxJpssTdZ1OCN3e', 'ATIVO'),
    (2, 2, '$2a$12$1CBAHD.wKOCpNFGnEMUfn.sMSf8Muag0NWrtrBBxJpssTdZ1OCN3e', 'ATIVO'),
    (3, 3, '$2a$12$1CBAHD.wKOCpNFGnEMUfn.sMSf8Muag0NWrtrBBxJpssTdZ1OCN3e', 'ATIVO');

INSERT INTO usuario_papel (usuario_id, papel_id) VALUES
    (1, 1),
    (1, 2),
    (1, 3),
    (2, 2),
    (3, 3);

ALTER SEQUENCE pessoa_seq RESTART WITH 4;
ALTER SEQUENCE papel_seq RESTART WITH 4;
ALTER SEQUENCE usuario_seq RESTART WITH 4;
