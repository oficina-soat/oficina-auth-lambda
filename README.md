# oficina-auth-lambda

Projeto standalone para o lambda de autenticacao da Oficina.

Esta arvore extrai o pacote `administrativo` do monolito e leva junto apenas o que ele realmente precisa para funcionar de forma isolada:

- `br.com.oficina.administrativo`
- tipos compartilhados minimos de `br.com.oficina.common.web`
- configuracao Quarkus para JWT e PostgreSQL
- seed minima de usuarios para dev/test
- testes do fluxo de autenticacao

## Runtime

O projeto usa Quarkus sobre AWS Lambda com handler nativo da extensao `quarkus-amazon-lambda`.

## Decisoes arquiteturais

### Acesso a banco bloqueante neste lambda

Este projeto usa acesso bloqueante ao PostgreSQL com `hibernate-orm-panache` e `jdbc-postgresql`.

A decisao foi tomada porque a entrada da aplicacao e um `RequestHandler` de AWS Lambda, portanto o fluxo ja nasce e termina de forma sincrona dentro de cada invocacao. Nesse contexto, manter o banco reativo acrescentava complexidade operacional e de codigo sem capturar um ganho relevante de throughput.

No projeto anterior, antes da separacao deste lambda, o modelo reativo fazia mais sentido porque o modulo ainda estava inserido em um contexto maior, com fronteiras e composicoes que justificavam esse estilo. Depois da extracao para um lambda isolado de autenticacao, com uma chamada curta e objetiva ao banco, o modelo bloqueante passou a ser a opcao mais simples e coerente.

## Contrato da Lambda

O projeto expõe um handler AWS Lambda e nao um recurso HTTP JAX-RS. A entrada e um JSON compativel com `AutenticarUsuarioRequest`:

```json
{
  "cpf": "84191404067",
  "password": "secret"
}
```

A saida segue `AutenticarUsuarioResponse`:

```json
{
  "access_token": "jwt",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

## Estrutura

```text
auth-lambda/
├── README.md
├── .gitignore
├── .mvn/
├── mvnw
├── mvnw.cmd
├── pom.xml
└── src/
```

## Dependencias externas

- PostgreSQL acessivel pelo lambda
- chaves JWT de assinatura e verificacao no ambiente produtivo

## Variaveis de ambiente

Banco:

- `QUARKUS_DATASOURCE_USERNAME`
- `QUARKUS_DATASOURCE_PASSWORD`
- `QUARKUS_DATASOURCE_JDBC_URL` opcional quando o deploy monta o JDBC URL a partir do RDS

JWT:

- `MP_JWT_VERIFY_PUBLICKEY` ou `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `SMALLRYE_JWT_SIGN_KEY` ou `SMALLRYE_JWT_SIGN_KEY_LOCATION`

No ambiente produtivo, o Quarkus le diretamente as variaveis do ambiente da Lambda. Isso permite publicar as chaves JWT inline como secret do GitHub Environment, sem depender de arquivos dentro da funcao.

## Desenvolvimento local

Para `dev` e `test`, o projeto usa:

- schema `drop-and-create`
- `import.sql` reduzido ao contexto de usuarios
- chaves JWT de teste em `src/test/resources/jwt/`
- mock event server do Quarkus Lambda em `%dev.quarkus.lambda.mock-event-server.dev-port=9080`

Para rodar em `dev`, gere um par local nao versionado:

```bash
./scripts/generate-dev-jwt-keys.sh
```

## Build JVM

```bash
cd auth-lambda
./mvnw test
./mvnw package
```

## Build nativo para runtime customizado da AWS

O projeto agora possui um profile Maven dedicado para gerar o pacote nativo do Lambda:

```bash
./mvnw clean package -Pnative-aws
```

Esse profile:

- habilita `quarkus.native.enabled`
- faz o native build em container para sempre gerar binario Linux
- usa `quarkus.native.march=compatibility` para evitar acoplamento ao CPU da maquina de build

Tambem existe um wrapper simples para CI ou uso local:

```bash
./scripts/build-native-lambda.sh
```

Ao final do build, os artefatos relevantes ficam em `target/`:

- `function.zip`: pacote pronto para deploy no runtime customizado da AWS Lambda
- `oficina-auth-lambda-native.zip`: copia com nome explicito para publicacao em pipeline
- `manage.sh`: script gerado pelo Quarkus para `create`, `update`, `delete` e `invoke`
- `sam.native.yaml`: template SAM para teste local do binario nativo

## Empacotamento Lambda

Para deploy nativo no runtime customizado da AWS Lambda, use o zip gerado em `target/function.zip` ou `target/oficina-auth-lambda-native.zip`.

Exemplo com o script gerado pelo Quarkus:

```bash
sh target/manage.sh native create
sh target/manage.sh native update
```

## Deploy com GitHub Actions

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) faz o deploy nativo da Lambda diretamente na AWS usando o GitHub Environment `lab`.

A autenticacao AWS segue o caminho mais simples para o laboratorio atual:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` quando o laboratorio entregar credenciais temporarias

Esses valores devem ficar em `secrets` do Environment `lab`. Como o laboratorio costuma recriar as credenciais a cada sessao, esses secrets precisam ser atualizados antes de um novo deploy.

Variaveis esperadas no mesmo Environment:

- `AWS_REGION`
- `EKS_CLUSTER_NAME`
- `DB_INSTANCE_IDENTIFIER`
- `LAMBDA_FUNCTION_NAME`

Variaveis opcionais:

- `DB_NAME`
- `DB_SSLMODE`
- `DB_SECURITY_GROUP_IDS`
- `LAMBDA_RUNTIME`
- `LAMBDA_ARCHITECTURE`
- `LAMBDA_MEMORY_SIZE`
- `LAMBDA_TIMEOUT`
- `LAMBDA_VPC_ID`
- `LAMBDA_SUBNET_IDS`
- `LAMBDA_SECURITY_GROUP_NAME`

Secrets esperados:

- `LAMBDA_ROLE_ARN`: obrigatorio apenas na primeira criacao da funcao
- `QUARKUS_DATASOURCE_USERNAME`
- `QUARKUS_DATASOURCE_PASSWORD`
- `QUARKUS_DATASOURCE_JDBC_URL`: opcional, para sobrescrever a URL montada automaticamente
- `MP_JWT_VERIFY_PUBLICKEY` ou `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `SMALLRYE_JWT_SIGN_KEY` ou `SMALLRYE_JWT_SIGN_KEY_LOCATION`

O deploy faz estas etapas:

- baixa o pacote nativo da Lambda
- descobre `vpc_id` e `subnet_ids` a partir do cluster EKS, salvo override
- descobre endpoint, porta e security groups do RDS pela instancia
- cria ou reutiliza um security group proprio da Lambda
- autoriza esse security group a acessar a porta do banco
- cria ou atualiza a Lambda com `VpcConfig`, runtime nativo e environment variables

Esse desenho segue a topologia dos projetos `oficina-infra-k8s` e `oficina-infra-db`: cluster e banco compartilham a mesma VPC de laboratorio, e o RDS permanece privado.

## Observacoes

- o projeto foi montado sem alterar o monolito atual
- o contrato implementado neste repositorio cobre apenas autenticacao
- as chaves de desenvolvimento em `src/main/resources/jwt/` nao sao versionadas; em producao use variaveis de ambiente apontando para chaves reais
- o build nativo exige um runtime de container compativel com o Quarkus native build, como Docker ou Podman
