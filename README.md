# oficina-auth-lambda

Repositório multi-módulo Maven da suíte Oficina para as Lambdas HTTP de autenticação e notificação, publicadas em runtime nativo do Quarkus e expostas pelo mesmo HTTP API Gateway do laboratório.

## Arquitetura

- `auth-lambda`
  - autenticação por CPF e senha
  - emissão de JWT
  - endpoints `POST /auth`, `POST /auth/token`, `GET /.well-known/openid-configuration` e `GET /.well-known/jwks.json`
  - integração com PostgreSQL/RDS e AWS Secrets Manager
- `notificacao-lambda`
  - envio de e-mail
  - endpoint `POST /notificacoes/email`
  - sem acoplamento direto com banco/JWT
- não há módulo Java compartilhado nesta Fase 1
  - o reuso ficou concentrado no `pom.xml` pai, scripts e workflows

## Estrutura do repositório

- `pom.xml`: POM pai com versão única do repositório
- `auth-lambda/`: aplicação Quarkus da Lambda de autenticação
- `notificacao-lambda/`: aplicação Quarkus da Lambda de notificação
- `scripts/`: automação de build nativo, cache S3, deploy, cleanup local e detecção de impacto
- `.github/workflows/`: build/deploy do laboratório
- `docs/github-actions.md`: convenções operacionais dos workflows

## Contratos HTTP preservados

Auth:

```text
POST /auth
POST /auth/token
GET /.well-known/openid-configuration
GET /.well-known/jwks.json
```

Notificação:

```text
POST /notificacoes/email
```

O endpoint de notificação não é mais publicado pelo mesmo runtime da autenticação. Cada Lambda responde apenas pelas suas próprias rotas.

## Observabilidade

O módulo `auth-lambda` já está preparado para observabilidade vendor-neutral:

- `service.name=oficina-auth-lambda`
- `service.namespace=oficina`
- `deployment.environment=lab` por padrão
- logs estruturados em JSON com correlação por `request_id`, `trace_id` e `span_id`
- tracing distribuído com OpenTelemetry, incluindo span interno do fluxo de autenticação
- métricas de autenticação:
  - `auth_requests_total`
  - `auth_failures_total`
  - `auth_latency_ms`

Env vars padronizadas:

- `OTEL_SERVICE_NAME`
- `OTEL_RESOURCE_ATTRIBUTES`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_PROTOCOL`
- `OTEL_TRACES_EXPORTER`
- `OTEL_METRICS_EXPORTER`
- `OTEL_LOGS_EXPORTER`
- `OFICINA_OBSERVABILITY_ENABLED`
- `OFICINA_OBSERVABILITY_JSON_LOGS_ENABLED`
- `OFICINA_OBSERVABILITY_METRICS_ENABLED`
- `OFICINA_OBSERVABILITY_TRACING_ENABLED`
- `DEPLOYMENT_ENVIRONMENT`

No deploy da Lambda, esse bloco pode ser injetado em `AUTH_LAMBDA_EXTRA_ENV_JSON` quando for necessário sobrescrever os defaults do código.

## Versionamento e artefatos

- a versão continua única no repositório e fica no `pom.xml` pai
- `main` não publica `-SNAPSHOT`
- o build nativo fechado fica no S3, em prefixos versionados por módulo
- se a versão atual já possui artefatos no S3 e a Lambda já está alinhada, a action não rebuilda nem redeploya
- quando o estado da AWS exigir novo build em `main`, o push precisa trazer incremento de versão no `pom.xml`

Como o `pom.xml` pai é comum, uma nova versão publicada normalmente gera artefatos versionados para as duas Lambdas.

## Detecção de impacto por módulo

A detecção fica em `scripts/detect-lambda-impacts.sh`.

Impacta `auth-lambda`:

- alterações em `auth-lambda/**`

Impacta `notificacao-lambda`:

- alterações em `notificacao-lambda/**`

Impacta ambas:

- `pom.xml`
- `mvnw`, `mvnw.cmd`, `.mvn/**`
- `scripts/**`
- `.github/workflows/**`

Mudanças apenas em documentação não disparam build nativo nem deploy.

## Desenvolvimento local

Auth:

```bash
./scripts/generate-dev-jwt-keys.sh
./mvnw -pl auth-lambda quarkus:dev
```

- mock event server: `http://localhost:9080`

Notificação:

```bash
./mvnw -pl notificacao-lambda quarkus:dev
```

- mock event server: `http://localhost:9082`

## Build nativo por módulo

```bash
./scripts/build-native-lambda.sh auth-lambda
./scripts/build-native-lambda.sh notificacao-lambda
```

Artefatos gerados:

- `auth-lambda/target/function.zip`
- `auth-lambda/target/oficina-auth-lambda-native.zip`
- `notificacao-lambda/target/function.zip`
- `notificacao-lambda/target/oficina-notificacao-lambda-native.zip`

## Deploy por módulo

```bash
./scripts/deploy-native-lambda.sh auth-lambda
./scripts/deploy-native-lambda.sh notificacao-lambda
```

Defaults operacionais:

- `auth-lambda`
  - função padrão: `oficina-auth-lambda-lab`
  - prefixo S3 padrão: `oficina/lab/lambda/oficina-auth-lambda`
  - anexa VPC por padrão
  - continua bootstrapando usuário do RDS e reutilizando `JWT_SECRET_NAME=oficina/lab/jwt`
- `notificacao-lambda`
  - função padrão: `oficina-notificacao-lambda-lab`
  - prefixo S3 padrão: `oficina/lab/lambda/oficina-notificacao-lambda`
  - anexa VPC por padrão no ambiente `lab`
  - usa por padrão um SG dedicado `${EKS_CLUSTER_NAME}-notificacao-lambda` para alcançar o MailHog privado criado pelo repositório `oficina-infra-k8s`
  - usa por padrão `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON={"QUARKUS_MAILER_FROM":"noreply@oficina.local","QUARKUS_MAILER_PORT":"1025","QUARKUS_MAILER_TLS":"false","QUARKUS_MAILER_START_TLS":"DISABLED"}` e resolve automaticamente o host privado do MailHog no deploy
  - quando sobrescrito para SMTP real externo, exige `QUARKUS_MAILER_FROM`; quando `QUARKUS_MAILER_MOCK` não for `true`, também exige `QUARKUS_MAILER_HOST`

Para configs específicas da função, os workflows e scripts usam nomes separados por Lambda, por exemplo:

- `AUTH_LAMBDA_FUNCTION_NAME`
- `AUTH_API_GATEWAY_ROUTE_KEYS`
- `AUTH_LAMBDA_ARTIFACT_PREFIX`
- `NOTIFICACAO_LAMBDA_FUNCTION_NAME`
- `NOTIFICACAO_API_GATEWAY_ROUTE_KEYS`
- `NOTIFICACAO_LAMBDA_ARTIFACT_PREFIX`
- `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON`

O JSON extra é mesclado nas env vars da Lambda e o script mantém uma lista de chaves gerenciadas para remover configs antigas em deploys seguintes.

Se `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` nao for informado, o deploy da `notificacao-lambda` em `lab` assume este fallback seguro:

```json
{
  "QUARKUS_MAILER_FROM": "noreply@oficina.local",
  "QUARKUS_MAILER_PORT": "1025",
  "QUARKUS_MAILER_TLS": "false",
  "QUARKUS_MAILER_START_TLS": "DISABLED"
}
```

Nesse modo, o script de deploy descobre o DNS privado do NLB interno `${EKS_CLUSTER_NAME}-mailhog-smtp` criado pela infra do laboratório e injeta `QUARKUS_MAILER_HOST` automaticamente. Se o NLB ou o SG dedicado não existirem, o deploy falha cedo em vez de abrir acesso mais amplo.

Exemplo para envio real:

```json
{
  "QUARKUS_MAILER_FROM": "noreply@oficina.example.com",
  "QUARKUS_MAILER_HOST": "smtp.oficina.example.com",
  "QUARKUS_MAILER_PORT": "587",
  "QUARKUS_MAILER_START_TLS": "REQUIRED",
  "QUARKUS_MAILER_USERNAME": "smtp-user",
  "QUARKUS_MAILER_PASSWORD": "smtp-password"
}
```

Exemplo para smoke test sem SMTP real:

```json
{
  "QUARKUS_MAILER_FROM": "noreply@oficina.example.com",
  "QUARKUS_MAILER_MOCK": "true"
}
```

## CI/CD

Resumo do fluxo:

- `develop`
  - roda `test`, `verify -DskipITs=false` e `bash -n scripts/*.sh`
  - cria ou atualiza o PR automático `develop -> main`
  - não faz build nativo
- `main`
  - consulta o S3 e a configuração das Lambdas na AWS
  - builda nativamente apenas os artefatos versionados ausentes
  - cria a Lambda quando a função ainda não existe
  - atualiza a Lambda quando a versão registrada em `OFICINA_LAMBDA_ARTIFACT_VERSION` não bate com o `pom.xml`
  - falha antes do build se a AWS exigir novo artefato e o push em `main` não tiver incrementado a versão

O workflow também pode ser executado manualmente em `main`, com `lambda_target=all|auth-lambda|notificacao-lambda`.

Detalhes operacionais: [docs/github-actions.md](docs/github-actions.md)

## Operações manuais

Build/deploy idempotente:

```text
Actions -> Build Deploy Lambda Lab -> Run workflow -> lambda_target=all|auth-lambda|notificacao-lambda
```

## Validação local

```bash
./mvnw test
./mvnw verify -DskipITs=false
bash -n scripts/*.sh
```
