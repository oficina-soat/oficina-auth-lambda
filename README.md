# oficina-auth-lambda

Projeto standalone para o lambda de autenticação da Oficina.

Esta árvore extrai o pacote `administrativo` do monólito e leva junto apenas o que ele realmente precisa para funcionar de forma isolada:

- `br.com.oficina.administrativo`
- tipos compartilhados mínimos de `br.com.oficina.common.web`
- configuração Quarkus para JWT e PostgreSQL
- seed mínima de usuários para dev/test
- testes do fluxo de autenticação

## Runtime

O projeto usa Quarkus sobre AWS Lambda com handler nativo da extensão `quarkus-amazon-lambda`.

## Decisões arquiteturais

### Acesso a banco bloqueante neste lambda

Este projeto usa acesso bloqueante ao PostgreSQL com `hibernate-orm-panache` e `jdbc-postgresql`.

A decisão foi tomada porque a entrada da aplicação é um `RequestHandler` de AWS Lambda, portanto o fluxo já nasce e termina de forma síncrona dentro de cada invocação. Nesse contexto, manter o banco reativo acrescentava complexidade operacional e de código sem capturar um ganho relevante de throughput.

No projeto anterior, antes da separação deste lambda, o modelo reativo fazia mais sentido porque o módulo ainda estava inserido em um contexto maior, com fronteiras e composições que justificavam esse estilo. Depois da extração para um lambda isolado de autenticação, com uma chamada curta e objetiva ao banco, o modelo bloqueante passou a ser a opção mais simples e coerente.

## Contrato da Lambda

O projeto expõe um handler AWS Lambda e não um recurso HTTP JAX-RS. A entrada é um JSON compatível com `AutenticarUsuarioRequest`:

```json
{
  "cpf": "84191404067",
  "password": "secret"
}
```

A saída segue `AutenticarUsuarioResponse`:

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

## Dependências externas

- PostgreSQL acessível pelo lambda
- chaves JWT de assinatura e verificação no ambiente produtivo

## Variáveis de ambiente

Banco:

- `QUARKUS_DATASOURCE_USERNAME`
- `QUARKUS_DATASOURCE_PASSWORD`
- `QUARKUS_DATASOURCE_JDBC_URL` opcional quando o deploy monta o JDBC URL a partir do RDS

JWT:

- `MP_JWT_VERIFY_PUBLICKEY` ou `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `SMALLRYE_JWT_SIGN_KEY` ou `SMALLRYE_JWT_SIGN_KEY_LOCATION`

No ambiente produtivo, o Quarkus lê diretamente as variáveis do ambiente da Lambda. Isso permite publicar as chaves JWT inline como secret do GitHub Environment, sem depender de arquivos dentro da função.

## Desenvolvimento local

Para `dev` e `test`, o projeto usa:

- schema `drop-and-create`
- `import.sql` reduzido ao contexto de usuários
- chaves JWT de teste em `src/test/resources/jwt/`
- mock event server do Quarkus Lambda em `%dev.quarkus.lambda.mock-event-server.dev-port=9080`

Para rodar em `dev`, gere um par local não versionado:

```bash
./scripts/generate-dev-jwt-keys.sh
```

Depois suba a aplicação:

```bash
./mvnw quarkus:dev
```

Com o mock event server ativo em `http://localhost:9080/_lambda_`, você pode testar localmente com:

```bash
curl -X POST http://localhost:9080/_lambda_ \
  -H 'Content-Type: application/json' \
  -d '{
    "cpf": "84191404067",
    "password": "secret"
  }'
```

Esse comando envia um evento diretamente para o handler da Lambda e retorna o `AutenticarUsuarioResponse` com o JWT quando as credenciais forem válidas.

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
- faz o native build em container para sempre gerar binário Linux
- usa `quarkus.native.march=compatibility` para evitar acoplamento ao CPU da máquina de build

Também existe um wrapper simples para CI ou uso local:

```bash
./scripts/build-native-lambda.sh
```

Ao final do build, os artefatos relevantes ficam em `target/`:

- `function.zip`: pacote pronto para deploy no runtime customizado da AWS Lambda
- `oficina-auth-lambda-native.zip`: cópia com nome explícito para publicação em pipeline
- `manage.sh`: script gerado pelo Quarkus para `create`, `update`, `delete` e `invoke`
- `sam.native.yaml`: template SAM para teste local do binário nativo

## Empacotamento Lambda

Para deploy nativo no runtime customizado da AWS Lambda, use o zip gerado em `target/function.zip` ou `target/oficina-auth-lambda-native.zip`.

Exemplo com o script gerado pelo Quarkus:

```bash
sh target/manage.sh native create
sh target/manage.sh native update
```

## Deploy com GitHub Actions

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) faz o deploy nativo da Lambda diretamente na AWS usando o GitHub Environment `lab`.

A autenticação AWS segue o caminho mais simples para o laboratório atual:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` quando o laboratório entregar credenciais temporárias

Esses valores devem ficar em `secrets` do Environment `lab`. Como o laboratório costuma recriar as credenciais a cada sessão, esses secrets precisam ser atualizados antes de um novo deploy.

Variáveis esperadas no mesmo Environment:

- `AWS_REGION`
- `EKS_CLUSTER_NAME`
- `DB_INSTANCE_IDENTIFIER`
- `LAMBDA_FUNCTION_NAME`

Variáveis opcionais:

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

- `LAMBDA_ROLE_ARN`: obrigatório apenas na primeira criação da função
- `QUARKUS_DATASOURCE_USERNAME`
- `QUARKUS_DATASOURCE_PASSWORD`
- `QUARKUS_DATASOURCE_JDBC_URL`: opcional, para sobrescrever a URL montada automaticamente
- `MP_JWT_VERIFY_PUBLICKEY` ou `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `SMALLRYE_JWT_SIGN_KEY` ou `SMALLRYE_JWT_SIGN_KEY_LOCATION`

O deploy faz estas etapas:

- baixa o pacote nativo da Lambda
- descobre `vpc_id` e `subnet_ids` a partir do cluster EKS, salvo override
- descobre endpoint, porta e security groups do RDS pela instância
- cria ou reutiliza um security group próprio da Lambda
- autoriza esse security group a acessar a porta do banco
- cria ou atualiza a Lambda com `VpcConfig`, runtime nativo e environment variables

Esse desenho segue a topologia dos projetos `oficina-infra-k8s` e `oficina-infra-db`: cluster e banco compartilham a mesma VPC de laboratório, e o RDS permanece privado.

## Observações

- o projeto foi montado sem alterar o monólito atual
- o contrato implementado neste repositório cobre apenas autenticação
- as chaves de desenvolvimento em `src/main/resources/jwt/` não são versionadas; em produção use variáveis de ambiente apontando para chaves reais
- o build nativo exige um runtime de container compatível com o Quarkus native build, como Docker ou Podman
