# oficina-auth-lambda

Lambda standalone de autenticação da Oficina, publicada em runtime nativo do Quarkus e exposta pelo HTTP API Gateway do laboratório.

O projeto isola o fluxo de autenticação que antes ficava acoplado ao monólito:

- endpoint HTTP para autenticar usuário por CPF e senha
- integração com PostgreSQL/RDS privado do ambiente `oficina-infra-db`
- publicação por HTTP API Gateway do ambiente `oficina-infra-k8s`
- emissão de JWT com SmallRye JWT
- testes unitários e de integração com H2
- pipeline que só executa build/release/deploy quando a versão da Lambda muda

## O que este projeto gerencia

- pacote nativo da Lambda (`target/function.zip`)
- GitHub Release da versão fechada da Lambda
- cópia operacional do pacote no S3, quando configurado
- função AWS Lambda
- security group dedicado da Lambda
- liberação do security group da Lambda no security group do RDS
- criação/atualização do usuário PostgreSQL exclusivo da Lambda no RDS
- secret `oficina/lab/database/auth-lambda` com a credencial do usuário da Lambda
- secret `oficina/lab/jwt` com o par de chaves JWT compartilhado com `../oficina-app`
- rota `AWS_PROXY` no HTTP API Gateway existente, quando `ATTACH_API_GATEWAY=true`
- log group da Lambda no cleanup manual

## O que este projeto não gerencia

- criação do RDS PostgreSQL
- criação do API Gateway
- criação da VPC/subnets compartilhadas
- criação de roles IAM do laboratório
- migrations completas do schema da aplicação
- rotação automática destrutiva de chaves JWT ou senha do banco sem opt-in

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

- `src/main/java`: domínio, persistência e endpoint HTTP
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
  "password": "12345"
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

O endpoint é implementado com Quarkus REST e publicado na Lambda pela extensão `quarkus-amazon-lambda-http`, compatível com HTTP API Gateway payload format `2.0`.

## Desenvolvimento local

Para `dev` e `test`, o projeto usa:

- schema `drop-and-create`
- carga reduzida em `import.sql`/`import-h2.sql`
- H2 nos testes
- chaves JWT locais ou de teste
- mock HTTP server do Quarkus Lambda em `http://localhost:9080`

Gere um par local não versionado:

```bash
./scripts/generate-dev-jwt-keys.sh
```

Suba a aplicação:

```bash
./mvnw quarkus:dev
```

Exemplo de invocação local:

```bash
curl -X POST http://localhost:9080/auth \
  -H 'Content-Type: application/json' \
  -d '{
    "cpf": "84191404067",
    "password": "12345"
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

Nos GitHub Actions, o pacote nativo é fechado primeiro em um GitHub Release `v<project.version>`. O asset publicado segue o padrão `oficina-auth-lambda-<project.version>-<LAMBDA_ARCHITECTURE>.zip`.

Depois da release, o workflow baixa o asset da própria release e só então envia a cópia operacional para o S3, usando chave baseada em `project.version`.

## Deploy

O deploy automatizado fica em [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- `develop`: cria ou atualiza o PR `develop -> main`; quando a release da versão atual ainda não existe, executa testes unitários e de integração antes de abrir ou atualizar o PR
- `main`: executa build nativo, GitHub Release, S3 e deploy quando a release da versão atual ainda não existe

Quando a release da versão atual já existe, commits novos ainda geram ou atualizam o PR para `main`, mas não geram build, release nem deploy. Em `main`, versões fechadas não podem terminar com `-SNAPSHOT`, e uma versão já publicada não é sobrescrita.

O deploy:

- baixa o pacote nativo da Lambda
- descobre VPC/subnets pelo RDS, com fallback opcional pelo EKS salvo overrides
- descobre endpoint, porta e security groups do RDS
- cria ou reutiliza o security group dedicado da Lambda
- autoriza o security group da Lambda no RDS
- cria ou reutiliza os secrets JWT `oficina/lab/jwt/privateKeyPem` e `oficina/lab/jwt/publicKeyPem`; se eles não existirem, gera um par RSA 2048 bits
- cria ou atualiza o usuário PostgreSQL próprio `oficina_auth_lambda`; se a senha ainda não existir, gera e salva sub-secrets sob `oficina/lab/database/auth-lambda/`
- cria ou atualiza a função Lambda com `VpcConfig` e variáveis do Quarkus, injetando os valores sensíveis no deploy por padrão para evitar dependência de rede com o Secrets Manager no startup
- cria ou atualiza a rota `POST /auth` no HTTP API Gateway existente
- adiciona permissão para o API Gateway invocar a Lambda

Por padrão, o deploy usa o prefixo `JWT_SECRET_NAME=oficina/lab/jwt` e materializa os sub-secrets `privateKeyPem` e `publicKeyPem`, resultando em `oficina/lab/jwt/privateKeyPem` e `oficina/lab/jwt/publicKeyPem`. No modo default `LAMBDA_SECRET_INJECTION_MODE=env-vars`, o deploy le esses secrets e grava as chaves diretamente nas env vars da Lambda, evitando NAT Gateway ou VPC Endpoint dedicado so para startup. Para rotacionar explicitamente o par JWT, use `ROTATE_JWT_SECRET=true`; tokens assinados com a chave anterior deixam de validar depois que os consumidores forem atualizados.

O usuário do banco é separado do usuário da aplicação principal. O deploy descobre o secret master gerenciado pelo RDS, libera temporariamente o IPv4 público do runner no security group do RDS quando `AUTO_ALLOW_DEPLOY_RUNNER_CIDR=true`, executa o bootstrap via `psql`, remove a regra temporária ao sair e injeta `QUARKUS_DATASOURCE_USERNAME`/`QUARKUS_DATASOURCE_PASSWORD` diretamente na Lambda por padrão. Para manter o comportamento antigo com leitura em runtime via AWS Secrets Manager, configure `LAMBDA_SECRET_INJECTION_MODE=runtime-secrets-manager`.

Detalhes de variáveis, secrets e workflows auxiliares: [docs/github-actions.md](docs/github-actions.md).

## Operações manuais

Redeploy da Lambda a partir da release já fechada da versão atual em `main`:

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
