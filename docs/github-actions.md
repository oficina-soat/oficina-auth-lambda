# GitHub Actions

O repositório usa o GitHub Environment `lab` e segue a mesma convenção dos repos `oficina-infra-k8s` e `oficina-infra-db`.

Workflows disponíveis:

- `./.github/workflows/ci.yml`
- `./.github/workflows/redeploy-lambda-lab.yml`
- `./.github/workflows/cleanup-lambda-lab.yml`

## Gatilho

- `push` em `develop`: verifica se a release da versão atual ainda não existe; quando houver deploy pendente, executa testes unitários e de integração e cria ou atualiza o PR `develop -> main`
- `pull_request` fechado e mergeado em `main`: executa build nativo, GitHub Release, armazenamento S3 e deploy quando a release da versão atual ainda não existir
- `workflow_dispatch` em `ci.yml`: respeita a branch selecionada; executa testes somente quando a release da versão atual ainda não existir; não executa build nativo, release nem deploy
- `workflow_dispatch` em `redeploy-lambda-lab.yml`: redeploy manual da release já fechada, somente quando a branch selecionada for `main`
- `workflow_dispatch` em `cleanup-lambda-lab.yml`: cleanup manual com confirmação `CLEANUP`

Os workflows que alteram a Lambda compartilham o grupo de `concurrency` `lab-lambda`, evitando cleanup e deploy simultâneos.

Quando a release da versão atual já existe, o `ci.yml` não executa testes, build, release nem deploy. Isso mantém commits posteriores sem incremento de `project.version` fora do caminho de deploy.

Para que a criação automática do PR funcione, o repositório precisa permitir que o `GITHUB_TOKEN` crie pull requests (`Settings -> Actions -> General -> Workflow permissions -> Allow GitHub Actions to create and approve pull requests`).

## Release e armazenamento do build nativo

O GitHub Release é a origem oficial da versão fechada da Lambda. Em `main`, quando a release da versão atual ainda não existe, o workflow:

1. gera o pacote nativo
2. cria a release `v<project.version>`
3. anexa o asset `oficina-auth-lambda-<project.version>-<LAMBDA_ARCHITECTURE>.zip`
4. baixa o asset da própria release
5. armazena esse mesmo pacote no S3, quando o bucket estiver configurado
6. faz o deploy da Lambda

No fluxo automático, os testes unitários e de integração rodam antes, no `push` em `develop`, e o PR só é criado ou atualizado se esses testes passarem. Quando o PR é aceito, o evento de PR mergeado em `main` começa no build nativo.

O PR automático de deploy não é aberto para versões `-SNAPSHOT`. Versões em `main` também não podem terminar com `-SNAPSHOT`. Se a versão mudar para uma release que já existe, o workflow falha e exige incremento de versão antes de gerar outro pacote.

Os workflows `CI/CD` e `Redeploy Lambda Lab` podem usar S3 para armazenar cópias operacionais:

- `target/function.zip`
- `target/oficina-auth-lambda-native.zip`

O bucket é definido por `LAMBDA_ARTIFACT_BUCKET`. Se essa variable não for informada, o workflow tenta usar `TF_STATE_BUCKET`. Quando nenhum dos dois valores existe, o pacote fica somente no GitHub Release e no artifact da execução.

A chave dos objetos é:

```text
<LAMBDA_ARTIFACT_PREFIX>/<LAMBDA_ARCHITECTURE>/<project.version>/function.zip
<LAMBDA_ARTIFACT_PREFIX>/<LAMBDA_ARCHITECTURE>/<project.version>/oficina-auth-lambda-native.zip
```

O prefixo default é `oficina/lab/lambda/oficina-auth-lambda`.

Isso significa que:

- o primeiro deploy de uma versão nova executa o build nativo, cria a release e, quando o bucket estiver configurado, salva no S3 o pacote baixado da release
- um redeploy manual baixa o pacote da release existente e pode repor a cópia no S3
- um commit novo sem mudança de versão não gera novo build nativo, release ou deploy

O deploy continua usando o artifact `lambda-native-package` da própria execução. O artifact é sempre derivado do GitHub Release antes de ser enviado ao S3 ou usado no deploy.

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

Use `Redeploy Lambda Lab` quando precisar republicar a Lambda a partir de uma versão já fechada, sem gerar novo build.

O workflow executa:

- download do asset `oficina-auth-lambda-<project.version>-<LAMBDA_ARCHITECTURE>.zip` da release `v<project.version>`
- armazenamento do pacote no S3, quando o bucket estiver configurado
- deploy da Lambda
- atualização da rota do API Gateway, quando habilitada

Selecione a branch `main` ao executar o workflow. Em outras branches, os jobs ficam bloqueados por guarda explícita. Se a release da versão atual não existir, o workflow falha.

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
