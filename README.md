# oficina-auth-lambda

Lambda standalone de autenticação da Oficina, publicada em runtime nativo do Quarkus e exposta pelo HTTP API Gateway do laboratório.

O projeto isola o fluxo de autenticação que antes ficava acoplado ao monólito:

- handler AWS Lambda para autenticar usuário por CPF e senha
- integração com PostgreSQL/RDS privado do ambiente `oficina-infra-db`
- publicação por HTTP API Gateway do ambiente `oficina-infra-k8s`
- emissão de JWT com SmallRye JWT
- testes unitários e de integração com H2
- pipeline que em `develop` executa apenas testes e em `main` executa testes, build nativo e deploy

## O que este projeto gerencia

- pacote nativo da Lambda (`target/function.zip`)
- função AWS Lambda
- security group dedicado da Lambda
- liberação do security group da Lambda no security group do RDS
- rota `AWS_PROXY` no HTTP API Gateway existente, quando `ATTACH_API_GATEWAY=true`
- log group da Lambda no cleanup manual

## O que este projeto não gerencia

- criação do RDS PostgreSQL
- criação do API Gateway
- criação da VPC/subnets compartilhadas
- criação de roles IAM do laboratório
- migrations completas do schema da aplicação

Esses recursos ficam nos repositórios irmãos:

- `../oficina-infra-db`: RDS PostgreSQL e bootstrap opcional de usuário/secret
- `../oficina-infra-k8s`: EKS, VPC compartilhada, ECR e HTTP API Gateway

## Convenções padronizadas com os repos de infra

- ambiente GitHub Actions: `lab`
- nome padrão da infra compartilhada: `eks-lab`
- banco padrão: `oficina-postgres-lab`
- VPC/subnets da Lambda: descobertas pelo DB subnet group do RDS por padrão
- API Gateway padrão: `<EKS_CLUSTER_NAME>-http-api`
- função padrão: `oficina-auth-lambda-lab`
- rota padrão no gateway: `POST /auth`
- runtime Lambda padrão: `provided.al2023`

## Estrutura

- `src/main/java`: domínio, persistência e handler Lambda
- `src/test/java`: testes unitários e de integração
- `src/main/resources/application.properties`: configuração Quarkus por perfil
- `scripts/build-native-lambda.sh`: build nativo do pacote Lambda
- `scripts/deploy-native-lambda.sh`: cria/atualiza Lambda, RDS SG e rota do API Gateway
- `scripts/cleanup-lambda.sh`: remove somente recursos operacionais da Lambda
- `.github/workflows/ci.yml`: CI/CD principal
- `.github/workflows/redeploy-lambda-lab.yml`: redeploy manual da Lambda
- `.github/workflows/cleanup-lambda-lab.yml`: cleanup manual da Lambda

## Contrato HTTP

Quando publicado pelo API Gateway, o endpoint padrão é:

```text
POST /auth
```

Entrada:

```json
{
  "cpf": "84191404067",
  "password": "secret"
}
```

Resposta de sucesso:

```json
{
  "access_token": "jwt",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

O handler recebe eventos `APIGatewayV2HTTPEvent` e responde `APIGatewayV2HTTPResponse`, compatível com HTTP API Gateway payload format `2.0`.

## Desenvolvimento local

Para `dev` e `test`, o projeto usa:

- schema `drop-and-create`
- carga reduzida em `import.sql`/`import-h2.sql`
- H2 nos testes
- chaves JWT locais ou de teste
- mock event server do Quarkus Lambda em `http://localhost:9080/_lambda_`

Gere um par local não versionado:

```bash
./scripts/generate-dev-jwt-keys.sh
```

Suba a aplicação:

```bash
./mvnw quarkus:dev
```

Exemplo de invocação pelo mock event server:

```bash
curl -X POST http://localhost:9080/_lambda_ \
  -H 'Content-Type: application/json' \
  -d '{
    "version": "2.0",
    "routeKey": "POST /auth",
    "rawPath": "/auth",
    "requestContext": {
      "http": {
        "method": "POST",
        "path": "/auth"
      }
    },
    "body": "{\"cpf\":\"84191404067\",\"password\":\"secret\"}",
    "isBase64Encoded": false
  }'
```

## Testes

```bash
./mvnw test
./mvnw verify -DskipITs=false
```

## Build nativo

O profile `native-aws` gera binário Linux em container para o runtime customizado da AWS Lambda:

```bash
./scripts/build-native-lambda.sh
```

Artefatos gerados:

- `target/function.zip`: pacote usado no deploy
- `target/oficina-auth-lambda-native.zip`: cópia com nome explícito para pipeline
- `target/manage.sh`: script gerado pelo Quarkus
- `target/sam.native.yaml`: template SAM para teste local

O build nativo exige Docker ou Podman disponível no ambiente.

Nos GitHub Actions, o pacote nativo é salvo em S3 com chave baseada no commit (`github.sha`). Se o mesmo commit for republicado pelo workflow `Redeploy Lambda Lab`, o pipeline restaura `target/function.zip` e `target/oficina-auth-lambda-native.zip` do S3 e pula o build nativo.

## Deploy

O deploy automatizado fica em [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- `develop`: executa testes unitários e de integração
- `main`: executa testes, build nativo e deploy na AWS

Em `main`, o build nativo só é executado quando ainda não existir pacote nativo no S3 para o commit atual.

O deploy:

- baixa o pacote nativo da Lambda
- descobre VPC/subnets pelo EKS, salvo overrides
- descobre endpoint, porta e security groups do RDS
- cria ou reutiliza o security group dedicado da Lambda
- autoriza o security group da Lambda no RDS
- cria ou atualiza a função Lambda com `VpcConfig` e variáveis do Quarkus
- cria ou atualiza a rota `POST /auth` no HTTP API Gateway existente
- adiciona permissão para o API Gateway invocar a Lambda

Detalhes de variáveis, secrets e workflows auxiliares: [docs/github-actions.md](docs/github-actions.md).

## Operações manuais

Redeploy completo da Lambda a partir da branch `main`:

```text
Actions -> Redeploy Lambda Lab -> Run workflow
```

Cleanup limitado aos recursos da Lambda:

```text
Actions -> Cleanup Lambda Lab -> Run workflow -> confirm_cleanup=CLEANUP
```

O cleanup preserva RDS, API Gateway, EKS, VPC e bucket de state. Ele remove a função Lambda, o log group, o security group dedicado da Lambda quando liberado, e as regras do RDS que apontam para esse security group.

## Validação local

```bash
./mvnw test
bash -n scripts/*.sh
```
