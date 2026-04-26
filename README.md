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
- `scripts/`: automação de build nativo, release cache, deploy, cleanup e detecção de impacto
- `.github/workflows/`: CI/CD, redeploy manual e cleanup manual
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

## Versionamento e release

- a versão continua única no repositório e fica no `pom.xml` pai
- `main` não publica `-SNAPSHOT`
- uma release existente `v<version>` não é sobrescrita
- toda nova release publica dois assets:
  - `oficina-auth-lambda-<version>-<arch>.zip`
  - `oficina-notificacao-lambda-<version>-<arch>.zip`

Como o `pom.xml` pai é comum, qualquer incremento de versão impacta os dois módulos e obriga a geração dos dois artefatos da release.

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
  - não anexa VPC por padrão
  - aceita configuração extra por `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON`

Para configs específicas da função, os workflows e scripts usam nomes separados por Lambda, por exemplo:

- `AUTH_LAMBDA_FUNCTION_NAME`
- `AUTH_API_GATEWAY_ROUTE_KEYS`
- `AUTH_LAMBDA_ARTIFACT_PREFIX`
- `NOTIFICACAO_LAMBDA_FUNCTION_NAME`
- `NOTIFICACAO_API_GATEWAY_ROUTE_KEYS`
- `NOTIFICACAO_LAMBDA_ARTIFACT_PREFIX`
- `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON`

O JSON extra é mesclado nas env vars da Lambda e o script mantém uma lista de chaves gerenciadas para remover configs antigas em deploys seguintes.

## CI/CD

Resumo do fluxo:

- `develop`
  - quando a release da versão atual ainda não existe, roda `test`, `verify` e `bash -n scripts/*.sh`
  - cria ou atualiza o PR automático `develop -> main`
- `main`
  - builda nativamente apenas os módulos impactados
  - cria a release GitHub com os dois assets da versão
  - grava no S3 apenas os módulos impactados
  - faz deploy apenas das Lambdas impactadas

Como a mudança de versão no `pom.xml` pai impacta ambos, toda release válida da Fase 1 acaba publicando os dois assets.

Detalhes operacionais: [docs/github-actions.md](docs/github-actions.md)

## Operações manuais

Redeploy da release já fechada:

```text
Actions -> Redeploy Lambda Lab -> Run workflow -> lambda_target=all|auth-lambda|notificacao-lambda
```

Cleanup operacional:

```text
Actions -> Cleanup Lambda Lab -> Run workflow -> confirm_cleanup=CLEANUP -> lambda_target=all|auth-lambda|notificacao-lambda
```

## Validação local

```bash
./mvnw test
./mvnw verify -DskipITs=false
bash -n scripts/*.sh
```
