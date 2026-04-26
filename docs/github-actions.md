# GitHub Actions

O repositório usa o GitHub Environment `lab` e agora opera duas Lambdas independentes no mesmo ciclo de release do repositório.

Workflows:

- `.github/workflows/ci.yml`
- `.github/workflows/redeploy-lambda-lab.yml`
- `.github/workflows/cleanup-lambda-lab.yml`

## Fluxo do `ci.yml`

`push` em `develop`:

- detecta versão e impacto por módulo
- se a release da versão atual ainda não existir, executa:
  - `./mvnw test`
  - `./mvnw verify -DskipITs=false`
  - `bash -n scripts/*.sh`
- cria ou atualiza o PR automático `develop -> main`

`push` em `main`:

- não aceita versão `-SNAPSHOT`
- não sobrescreve release já existente
- builda nativamente apenas os módulos impactados
- cria a release `v<project.version>`
- publica os dois assets da versão
- armazena no S3 apenas os módulos impactados
- faz deploy apenas das Lambdas impactadas

`workflow_dispatch` em `ci.yml`:

- executa a mesma detecção de versão/impacto
- roda os testes apenas quando a release da versão atual ainda não existe
- não cria release nem faz deploy

## Regras de impacto

Impacta `auth-lambda`:

- `auth-lambda/**`

Impacta `notificacao-lambda`:

- `notificacao-lambda/**`

Impacta ambas:

- `pom.xml`
- `mvnw`, `mvnw.cmd`, `.mvn/**`
- `scripts/**`
- `.github/workflows/**`

Essa regra cobre a Fase 1:

- versão única no repositório
- build/release únicos por versão
- deploy seletivo só para Lambdas impactadas

Como a mudança de versão acontece no `pom.xml` pai, toda release válida impacta os dois módulos e publica os dois assets.

## Assets da release

Cada release publica:

- `oficina-auth-lambda-<version>-<LAMBDA_ARCHITECTURE>.zip`
- `oficina-notificacao-lambda-<version>-<LAMBDA_ARCHITECTURE>.zip`
- `checksums.txt`

O GitHub Release é a origem oficial do pacote fechado. Depois da criação da release, o workflow baixa de volta os assets e só então replica para S3 e usa no deploy.

## Prefixos S3

Auth:

```text
<AUTH_LAMBDA_ARTIFACT_PREFIX>/<arch>/<version>/function.zip
<AUTH_LAMBDA_ARTIFACT_PREFIX>/<arch>/<version>/oficina-auth-lambda-native.zip
```

Notificação:

```text
<NOTIFICACAO_LAMBDA_ARTIFACT_PREFIX>/<arch>/<version>/function.zip
<NOTIFICACAO_LAMBDA_ARTIFACT_PREFIX>/<arch>/<version>/oficina-notificacao-lambda-native.zip
```

Defaults:

- `AUTH_LAMBDA_ARTIFACT_PREFIX=oficina/lab/lambda/oficina-auth-lambda`
- `NOTIFICACAO_LAMBDA_ARTIFACT_PREFIX=oficina/lab/lambda/oficina-notificacao-lambda`

## Variáveis e secrets principais

Compartilhados:

- `AWS_REGION`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN`
- `LAMBDA_ARCHITECTURE`
- `LAMBDA_RUNTIME`
- `LAMBDA_MEMORY_SIZE`
- `ATTACH_API_GATEWAY`
- `API_GATEWAY_PAYLOAD_FORMAT_VERSION`
- `API_GATEWAY_TIMEOUT_MILLISECONDS`

Auth:

- `AUTH_LAMBDA_FUNCTION_NAME`
- `AUTH_LAMBDA_ROLE_ARN`
- `AUTH_LAMBDA_ATTACH_VPC`
- `AUTH_LAMBDA_VPC_ID`
- `AUTH_LAMBDA_SUBNET_IDS`
- `AUTH_LAMBDA_SECURITY_GROUP_NAME`
- `AUTH_API_GATEWAY_ID`
- `AUTH_API_GATEWAY_NAME`
- `AUTH_API_GATEWAY_ROUTE_KEYS`
- `AUTH_DB_USER`
- `AUTH_DB_SECRET_NAME`
- `JWT_SECRET_NAME`
- `JWT_SECRET_SOURCE`
- `LAMBDA_SECRET_INJECTION_MODE`
- `OFICINA_AUTH_ISSUER`
- `OFICINA_AUTH_AUDIENCE`
- `OFICINA_AUTH_SCOPE`
- `OFICINA_AUTH_KEY_ID`

Notificação:

- `NOTIFICACAO_LAMBDA_FUNCTION_NAME`
- `NOTIFICACAO_LAMBDA_ROLE_ARN`
- `NOTIFICACAO_LAMBDA_ATTACH_VPC`
- `NOTIFICACAO_LAMBDA_VPC_ID`
- `NOTIFICACAO_LAMBDA_SUBNET_IDS`
- `NOTIFICACAO_LAMBDA_SECURITY_GROUP_NAME`
- `NOTIFICACAO_API_GATEWAY_ID`
- `NOTIFICACAO_API_GATEWAY_NAME`
- `NOTIFICACAO_API_GATEWAY_ROUTE_KEYS`
- `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON`

`NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` serve para injetar configuração específica da função, como parâmetros do mailer. O deploy grava também `OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS` para conseguir remover chaves extras antigas em atualizações futuras.

Se esse valor não estiver configurado no GitHub Environment `lab`, os workflows usam o fallback:

```json
{"QUARKUS_MAILER_FROM":"noreply@oficina.local","QUARKUS_MAILER_PORT":"1025","QUARKUS_MAILER_TLS":"false","QUARKUS_MAILER_START_TLS":"DISABLED"}
```

Com esse fallback, a `notificacao-lambda` passa a subir com `NOTIFICACAO_LAMBDA_ATTACH_VPC=true`, reutiliza o SG `NOTIFICACAO_LAMBDA_SECURITY_GROUP_NAME` e tenta resolver automaticamente o DNS privado do NLB interno `${EKS_CLUSTER_NAME}-mailhog-smtp`, criado pelo repositório `oficina-infra-k8s`.

Quando `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` for sobrescrito para SMTP real externo, o JSON deve incluir `QUARKUS_MAILER_FROM`. Quando `QUARKUS_MAILER_MOCK` não estiver em `true`, também deve incluir `QUARKUS_MAILER_HOST`.

## Redeploy manual

`Redeploy Lambda Lab` aceita:

- `lambda_target=all`
- `lambda_target=auth-lambda`
- `lambda_target=notificacao-lambda`

O workflow:

- resolve a versão atual do `pom.xml`
- baixa o asset da release correspondente ao módulo selecionado
- opcionalmente replica para S3
- executa o deploy do módulo selecionado

## Cleanup manual

`Cleanup Lambda Lab` exige:

- `confirm_cleanup=CLEANUP`
- `lambda_target=all|auth-lambda|notificacao-lambda`

O cleanup remove apenas os recursos operacionais da Lambda selecionada:

- função Lambda
- log group
- security group dedicado da função, quando aplicável
- regra de acesso ao RDS criada para a Lambda de auth
