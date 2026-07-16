# Migração para o banco exclusivo de autenticação

O destino canônico é o database `oficina_auth`, acessado somente pela role
`oficina_auth_user`, com a credencial JSON em
`oficina/lab/database/oficina-auth-lambda`. `auth-lambda` e `auth-sync-lambda`
consomem o mesmo secret; nenhum outro serviço deve receber essa credencial.

## Ordem segura no lab

1. Aplicar o Terraform e executar o bootstrap do `oficina-infra`. O database, a
   role de privilégio mínimo e o secret exclusivo serão criados sem alterar `app`.
2. Com as Lambdas ainda apontando para `app`, executar
   `scripts/migrate-auth-database.sh` a partir de um executor com rota para o RDS.
   As URLs administrativas devem ser obtidas em tempo de execução e nunca
   versionadas. O destino precisa estar vazio.
3. Validar as contagens emitidas pelo script e executar uma consulta somente
   leitura no destino.
4. Publicar a versão `1.2.0` das Lambdas. Os defaults do workflow passam a usar
   `oficina_auth`, `oficina_auth_user` e o secret exclusivo.
5. Validar login administrativo, ativação de credencial, rejeição de senha
   inválida e sincronização idempotente de usuário.

O script não remove nem altera os dados de origem. Ele falha se o destino já
contiver tabelas e compara as quantidades das tabelas de autenticação após a
restauração.

## Rollback

Interrompa eventos de sincronização durante a troca. Se a homologação falhar,
republique a versão anterior das Lambdas com `DB_NAME=app`,
`AUTH_DB_USER=oficina_auth_lambda` e
`AUTH_DB_SECRET_NAME=oficina/lab/database/auth-lambda`. Como a origem é
preservada, o rollback não depende de restore. Antes de retomar a sincronização,
confirme que não houve escrita aceita exclusivamente no banco novo; se houve,
reconcilie os registros por identificador canônico.
