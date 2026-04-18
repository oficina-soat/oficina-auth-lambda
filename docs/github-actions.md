# GitHub Actions

O repositório usa o GitHub Environment `lab` e segue a mesma convenção dos repos `oficina-infra-k8s` e `oficina-infra-db`.

Workflows disponíveis:

- `./.github/workflows/ci.yml`
- `./.github/workflows/redeploy-lambda-lab.yml`
- `./.github/workflows/cleanup-lambda-lab.yml`

## Gatilho

- `push` em `develop`: testes unitários e de integração
- `push` em `main`: testes, build nativo e deploy
- `workflow_dispatch` em `ci.yml`: respeita a branch selecionada; em `develop` não faz deploy
- `workflow_dispatch` em `redeploy-lambda-lab.yml`: redeploy manual, somente quando a branch selecionada for `main`
- `workflow_dispatch` em `cleanup-lambda-lab.yml`: cleanup manual com confirmação `CLEANUP`

Os workflows que alteram a Lambda compartilham o grupo de `concurrency` `lab-lambda`, evitando cleanup e deploy simultâneos.

## Armazenamento do build nativo

Os workflows `CI/CD` e `Redeploy Lambda Lab` podem usar S3 para armazenar:

- `target/function.zip`
- `target/oficina-auth-lambda-native.zip`

O bucket é definido por `LAMBDA_ARTIFACT_BUCKET`. Se essa variable não for informada, o workflow tenta usar `TF_STATE_BUCKET`. Quando nenhum dos dois valores existe, o workflow pula o cache S3 e executa o build nativo normalmente.

A chave dos objetos é:

```text
<LAMBDA_ARTIFACT_PREFIX>/<LAMBDA_ARCHITECTURE>/<github.sha>/function.zip
<LAMBDA_ARTIFACT_PREFIX>/<LAMBDA_ARCHITECTURE>/<github.sha>/oficina-auth-lambda-native.zip
```

O prefixo default é `oficina/lab/lambda/oficina-auth-lambda`.

Isso significa que:

- o primeiro deploy de um commit novo executa o build nativo e, quando o bucket estiver configurado, salva o pacote no S3
- um redeploy manual do mesmo commit restaura o pacote do S3 e pula o build nativo, quando o bucket estiver configurado
- qualquer commit novo gera uma chave nova e força um novo build nativo

O deploy continua usando o artifact `lambda-native-package` gerado ou restaurado no próprio workflow. O S3 serve como armazenamento durável entre execuções, mas é opcional.

## Autenticação AWS

Secrets obrigatórios:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`

Secret opcional:

- `AWS_SESSION_TOKEN`: necessário quando o laboratório entregar credenciais temporárias

Como o laboratório costuma recriar as credenciais a cada sessão, atualize esses secrets antes de executar deploy, redeploy ou cleanup.

## Secrets da Lambda

- `LAMBDA_ROLE_ARN`: obrigatório apenas na primeira criação da função
- `QUARKUS_DATASOURCE_USERNAME`
- `QUARKUS_DATASOURCE_PASSWORD`
- `QUARKUS_DATASOURCE_JDBC_URL`: opcional; quando ausente, o deploy monta a URL a partir do RDS
- `MP_JWT_VERIFY_PUBLICKEY` ou `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `SMALLRYE_JWT_SIGN_KEY` ou `SMALLRYE_JWT_SIGN_KEY_LOCATION`

## Variables principais

- `AWS_REGION`: default `us-east-1`
- `EKS_CLUSTER_NAME`: default `eks-lab`; usado como fallback para descobrir VPC/subnets e para montar o nome default do API Gateway
- `DB_INSTANCE_IDENTIFIER`: default `oficina-postgres-lab`
- `DB_IDENTIFIER`: fallback aceito para manter compatibilidade com `oficina-infra-db`
- `DB_NAME`: opcional; sobrescreve o database name retornado pelo RDS
- `DB_SSLMODE`: default `require`
- `DB_SECURITY_GROUP_IDS`: lista CSV ou JSON, opcional
- `LAMBDA_FUNCTION_NAME`: default `oficina-auth-lambda-lab`
- `LAMBDA_RUNTIME`: default `provided.al2023`
- `LAMBDA_ARCHITECTURE`: default `x86_64`
- `LAMBDA_MEMORY_SIZE`: default `256`
- `LAMBDA_TIMEOUT`: default `15`
- `LAMBDA_VPC_ID`: override opcional; quando ausente, o deploy tenta usar a VPC do RDS
- `LAMBDA_SUBNET_IDS`: lista CSV ou JSON, override opcional; quando ausente, o deploy tenta usar as subnets do DB subnet group do RDS
- `LAMBDA_SECURITY_GROUP_NAME`: default `<LAMBDA_FUNCTION_NAME>-sg`
- `LAMBDA_ARTIFACT_BUCKET`: bucket S3 opcional para armazenar o pacote nativo; fallback para `TF_STATE_BUCKET`
- `LAMBDA_ARTIFACT_PREFIX`: prefixo S3 dos pacotes nativos. Default `oficina/lab/lambda/oficina-auth-lambda`

## Variables do API Gateway

- `ATTACH_API_GATEWAY`: default `true`
- `API_GATEWAY_ID`: ID do HTTP API existente
- `API_GATEWAY_NAME`: default `<EKS_CLUSTER_NAME>-http-api`
- `API_GATEWAY_ROUTE_KEY`: default `POST /auth`
- `API_GATEWAY_PAYLOAD_FORMAT_VERSION`: default `2.0`
- `API_GATEWAY_TIMEOUT_MILLISECONDS`: default `30000`

Quando `ATTACH_API_GATEWAY=true`, o deploy cria ou atualiza somente a rota informada por `API_GATEWAY_ROUTE_KEY` no gateway existente. Ele não cria nem remove o API Gateway.

## Redeploy manual

Use `Redeploy Lambda Lab` quando precisar republicar a Lambda sem novo merge em `main`.

O workflow executa:

- testes unitários
- testes de integração
- build nativo, somente quando o pacote do commit atual não existir no S3
- deploy da Lambda
- atualização da rota do API Gateway, quando habilitada

Selecione a branch `main` ao executar o workflow. Em outras branches, os jobs ficam bloqueados por guarda explícita.

## Cleanup manual

Use `Cleanup Lambda Lab` quando precisar remover apenas os recursos operacionais desta Lambda.

O workflow exige `confirm_cleanup=CLEANUP` e executa `scripts/cleanup-lambda.sh`.

Ele remove:

- função Lambda `LAMBDA_FUNCTION_NAME`
- log group `/aws/lambda/<LAMBDA_FUNCTION_NAME>`
- security group dedicado da Lambda, quando não houver ENIs pendentes
- regras de entrada no security group do RDS que apontam para o security group da Lambda

Ele preserva:

- RDS
- API Gateway
- rotas do API Gateway
- EKS
- VPC e subnets compartilhadas
- bucket/state do Terraform

## Integração com os repos de infra

O RDS é descoberto pelo `DB_INSTANCE_IDENTIFIER` criado em `../oficina-infra-db`. O default deste repo é `oficina-postgres-lab`, mesmo default do repo de banco. A VPC e as subnets da Lambda também são descobertas a partir do DB subnet group do RDS, salvo quando `LAMBDA_VPC_ID` e `LAMBDA_SUBNET_IDS` forem informados explicitamente.

O API Gateway é descoberto por `API_GATEWAY_ID` ou por `API_GATEWAY_NAME`. O default por nome segue `../oficina-infra-k8s`: `<EKS_CLUSTER_NAME>-http-api`.

Para deixar a rota sob controle do Terraform do repo `oficina-infra-k8s`, configure `ATTACH_API_GATEWAY=false` neste repo e informe a rota no `API_GATEWAY_LAMBDA_ROUTES` daquele repo.
