# Instruções para agentes Codex

Este projeto implementa a Lambda de autenticação da Oficina com Java 25, Quarkus 3.x, Maven Wrapper e deploy nativo na AWS Lambda.

## Regras gerais

- Use sempre `./mvnw`, nunca `mvn`, salvo se houver motivo explícito.
- Preserve a organização existente em `domain`, `persistence`, `resource` e `config`.
- Quando precisar definir nomes compartilhados de environments, secrets, variáveis ou recursos, consulte antes `../oficina-app`, `../oficina-infra-k8s` e `../oficina-infra-db`.
- Não assuma que Docker, Podman, credenciais AWS ou acesso à infraestrutura remota estão disponíveis.
- Prefira comandos reais de validação em vez de inferências.
- Quando alterar código Java, execute ao menos `./mvnw test`.
- Quando a mudança afetar integração, configuração Quarkus, persistência ou contrato HTTP, execute também `./mvnw verify -DskipITs=false`.
- Quando alterar scripts em `scripts/`, valide com `bash -n scripts/*.sh`.
- Antes de acionar novo build nativo, release ou deploy, confirme se a mudança exige incremento de versão em `pom.xml`.

## Maven Wrapper

Comandos preferenciais:

```bash
./mvnw -version
./mvnw test
./mvnw verify -DskipITs=false
./mvnw package
./mvnw quarkus:dev
```

Use `./mvnw test` para validação rápida.

Use `./mvnw verify -DskipITs=false` quando a alteração puder afetar integração Quarkus, banco, JWT, empacotamento da Lambda ou contrato HTTP.

## Build nativo e scripts

Scripts locais relevantes:

```bash
./scripts/generate-dev-jwt-keys.sh
./scripts/build-native-lambda.sh
```

O build nativo depende de container runtime disponível no ambiente.

Scripts de deploy, cleanup e publicação em S3 alteram infraestrutura AWS. Só execute esses fluxos quando a tarefa realmente exigir.

## AWS

Use AWS CLI para validação do ambiente quando necessário:

```bash
aws sts get-caller-identity
```

Prefira comandos de leitura para confirmar estado de Lambda, API Gateway, Secrets Manager e demais recursos antes de assumir qualquer comportamento remoto.

## Git

Ao concluir alterações no escopo da tarefa, prepare o commit explicitamente com:

```bash
git add <arquivos-da-tarefa>
git commit -m "<tipo>: <resumo>"
```

Prefira mensagens curtas em português seguindo Conventional Commits.
