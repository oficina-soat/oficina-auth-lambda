INSERT INTO pessoa (id, documento, tipo_pessoa, nome) VALUES
    (1, '84191404067', 'FISICA', 'Administrador Laboratorio'),
    (2, '36655462007', 'FISICA', 'Mecanico Laboratorio'),
    (3, '17245011010', 'FISICA', 'Recepcionista Laboratorio');

INSERT INTO papel (id, nome) VALUES
    (1, 'administrativo'),
    (2, 'mecanico'),
    (3, 'recepcionista');

INSERT INTO usuario (id, pessoa_id, password, status) VALUES
    (1, 1, '$2a$10$hks0l8Lcuh/hWWFwuffKg.GE1ZnPcESJl/sEGnyy9yAXgr1gOTQ3a', 'ATIVO'),
    (2, 2, '$2a$10$hks0l8Lcuh/hWWFwuffKg.GE1ZnPcESJl/sEGnyy9yAXgr1gOTQ3a', 'ATIVO'),
    (3, 3, '$2a$10$hks0l8Lcuh/hWWFwuffKg.GE1ZnPcESJl/sEGnyy9yAXgr1gOTQ3a', 'ATIVO');

INSERT INTO usuario_papel (usuario_id, papel_id) VALUES
    (1, 1),
    (1, 2),
    (1, 3),
    (2, 2),
    (3, 3);

SELECT setval('usuario_seq', (SELECT MAX(id) FROM public.usuario));
SELECT setval('pessoa_seq', (SELECT MAX(id) FROM public.pessoa));
SELECT setval('papel_seq', (SELECT MAX(id) FROM public.papel));
