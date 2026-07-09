# GitHub Actions

O repositório mantém dois workflows sem declarar GitHub Environment, para evitar aprovações manuais nos jobs. As variáveis e secrets de deploy devem estar em nível de repositório ou organização:

- `.github/workflows/open-pr-to-main.yml` (`Open PR To Main`)
- `.github/workflows/deploy-lambda-lab.yml` (`Deploy Lambda Lab`)

No fluxo principal da suíte, `Deploy Lambda Lab` é disparado pelo encadeamento `oficina-infra-k8s -> oficina-infra-db -> oficina-auth-lambda`. A execução manual fica reservada para operação pontual das Lambdas em `main`.

Depois que a suíte estiver implantada, a validação fim-a-fim principal deve ser rodada no `oficina-app`:

```bash
cd ../oficina-app
MODO_ACESSO=aws ./scripts/validar-metricas-paineis.sh
```

## Fluxo

`push` em `develop`:

- executa `bash -n scripts/*.sh`
- executa `./mvnw -B test -DfailIfNoTests=false`
- executa `./mvnw -B verify -DskipITs=false -DfailIfNoTests=false`
- cria ou atualiza o PR automático `develop -> main`
- não faz build nativo

`push` em `main`:

- não aceita versão `-SNAPSHOT`
- resolve o bucket de artefatos por `LAMBDA_ARTIFACT_BUCKET`, `TERRAFORM_SHARED_DATA_BUCKET_NAME`, `TF_STATE_BUCKET` ou pelo padrão compartilhado `tf-shared-<shared_infra_name>-<account-id>-<region>`
- falha se o bucket não existir ou não estiver acessível
- verifica no S3 se `function.zip` e o pacote nomeado da versão atual existem para cada Lambda
- verifica se a função Lambda existe e se `OFICINA_LAMBDA_ARTIFACT_VERSION` bate com a versão atual do `pom.xml`
- builda e armazena no S3 apenas artefatos versionados ausentes
- restaura o pacote do S3 antes do deploy
- cria a Lambda quando ela não existe
- atualiza a Lambda quando a versão registrada nela está ausente ou diferente
- falha antes do build quando a AWS exige novo artefato e o push em `main` não incrementou `project.version`

`workflow_dispatch`:

- deve ser executado em `main`
- aceita `lambda_target=all|auth-lambda|notificacao-lambda`
- usa a mesma resolução de estado da AWS para decidir build e deploy

## Estado AWS

O S3 é a fonte de verdade para o pacote nativo fechado.

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

O deploy grava `OFICINA_LAMBDA_ARTIFACT_VERSION` nas variáveis da Lambda. Esse valor permite que a action pule deploys repetidos quando a função já aponta para a versão do `pom.xml`.

## Variáveis e secrets principais

Compartilhados:

- `AWS_REGION`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN`
- `SHARED_INFRA_NAME`
- `TF_STATE_BUCKET`
- `TERRAFORM_SHARED_DATA_BUCKET_NAME`
- `LAMBDA_ARTIFACT_BUCKET`
- `LAMBDA_ARCHITECTURE`
- `LAMBDA_RUNTIME`
- `LAMBDA_MEMORY_SIZE`
- `ATTACH_API_GATEWAY`
- `API_GATEWAY_PAYLOAD_FORMAT_VERSION`
- `API_GATEWAY_TIMEOUT_MILLISECONDS`

Auth:

- `DB_NAME`
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
- `AUTH_LAMBDA_EXTRA_ENV_JSON`

Quando `DB_NAME` não é informado, o workflow `Deploy Lambda Lab` usa `app`, que é o database legado esperado pela `auth-lambda`. Se o RDS não retornar `DBName`, o script de deploy também assume `app` e garante a existência do database antes de bootstrapar o usuário `AUTH_DB_USER`. Esse bootstrap não executa migrations nem seed de laboratório.

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

`NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` serve para injetar configuração específica da função, como parâmetros do mailer. O deploy grava também `OFICINA_LAMBDA_MANAGED_EXTRA_ENV_KEYS` para remover chaves extras antigas em atualizações futuras.

Quando `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` não for sobrescrito, o workflow usa o fallback:

```json
{"QUARKUS_MAILER_FROM":"noreply@oficina.local","QUARKUS_MAILER_PORT":"1025","QUARKUS_MAILER_TLS":"false","QUARKUS_MAILER_START_TLS":"DISABLED"}
```

Com esse fallback, a `notificacao-lambda` tenta resolver automaticamente o DNS privado do NLB interno `${EKS_CLUSTER_NAME}-mailhog-smtp`. Quando o NLB existe, o deploy injeta `QUARKUS_MAILER_HOST` e exige VPC para acessar o host privado. Quando o NLB padrão não existe, o deploy injeta `QUARKUS_MAILER_MOCK=true` e segue sem SMTP real.

Quando `NOTIFICACAO_LAMBDA_EXTRA_ENV_JSON` for sobrescrito para SMTP real externo, o JSON deve incluir `QUARKUS_MAILER_FROM`. Quando `QUARKUS_MAILER_MOCK` não estiver em `true`, também deve incluir `QUARKUS_MAILER_HOST`. Se `NOTIFICACAO_MAILHOG_NLB_NAME` for informado explicitamente, a ausência desse NLB continua falhando cedo.
