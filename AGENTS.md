# AGENTS.md

## Contexto

Este repositório implementa a Lambda de autenticação da Oficina com Quarkus, publicada como runtime nativo na AWS Lambda e exposta via HTTP API Gateway.

Stack atual do projeto:

- Java 25
- Quarkus 3.x
- Maven Wrapper (`./mvnw`)
- Quarkus REST, Panache, SmallRye JWT e Amazon Lambda HTTP
- PostgreSQL em produção e H2 nos testes

O código principal está concentrado em `src/main/java/br/com/oficina/autenticacao`, com testes em `src/test/java/br/com/oficina/autenticacao` e scripts operacionais em `scripts/`.

Este repositório faz parte de uma suíte maior. Assuma que, quando presentes na mesma raiz deste diretório, os repositórios irmãos relevantes são:

- `../oficina-app`
- `../oficina-infra-k8s`
- `../oficina-infra-db`

Quando esses repositórios estiverem disponíveis, eles devem ser consultados para manter consistência de nomes e contratos compartilhados da suíte, especialmente:

- nomes de environments
- nomes de secrets
- nomes de variáveis de ambiente
- identificadores de recursos compartilhados
- convenções de integração entre aplicação e infraestrutura

## Diretrizes Gerais

- Preserve a arquitetura já usada no projeto: camadas de `domain`, `persistence`, `resource` e `config`.
- Prefira mudanças pequenas, objetivas e compatíveis com o padrão existente.
- Ao adicionar ou ajustar integração de infraestrutura, dê preferência a extensões oficiais do Quarkus ou do ecossistema Quarkiverse antes de recorrer a SDKs, código customizado ou scripts adicionais.
- Evite introduzir dependências novas sem necessidade clara.
- Mantenha compatibilidade com o fluxo atual de build nativo e deploy da Lambda.
- Quando houver dúvida sobre nomes que precisam ser iguais entre serviços e infra, consulte primeiro `../oficina-app`, `../oficina-infra-k8s` e `../oficina-infra-db` antes de definir novos valores.

## Implementação

- Use Java 25 de forma idiomática, mas sem introduzir complexidade desnecessária.
- Siga os padrões já presentes no código para nomes, organização de pacotes e estilo de testes.
- Ao mexer em endpoints, preserve o contrato HTTP documentado no `README.md`, salvo quando a mudança exigir ajuste explícito de contrato.
- Ao mexer em configuração, considere os perfis `dev`, `test` e `prod` já definidos em `src/main/resources/application.properties`.
- Se houver erro simples, warning simples ou ajuste mecânico evidente no escopo da tarefa, resolva junto em vez de deixar pendência.

## Validação

Antes de encerrar uma alteração, execute a validação compatível com o impacto da mudança:

- `./mvnw test`
- `./mvnw verify -DskipITs=false` quando a mudança afetar integração, configuração Quarkus, persistência ou contrato HTTP
- `bash -n scripts/*.sh` quando houver alteração em scripts

Se alguma verificação não puder ser executada, registre isso claramente na resposta final.

## Versionamento e Build

Este projeto depende de versionamento explícito para gerar novo build/release/deploy.

- A versão da aplicação fica em `pom.xml`.
- Sempre que for necessário refazer o build nativo, gerar nova release ou disparar novo ciclo de publicação da Lambda, atualize a versão do projeto antes.
- Não reutilize a mesma versão para tentar forçar novo pacote da Lambda.
- Ao alterar algo que impacte artefato publicado, confirme se a mudança também exige incremento de versão.

Comandos relevantes:

- `./mvnw quarkus:dev`
- `./scripts/build-native-lambda.sh`

O build nativo usa o profile `native-aws` e depende de container runtime disponível no ambiente.

## Commits

Sempre que houver alterações no repositório ao final da tarefa, crie commit antes de encerrar a resposta.

- Antes do commit, adicione ao Git todos os arquivos novos criados na tarefa com `git add`.
- Ao preparar o commit, inclua também os arquivos modificados relacionados à alteração concluída.
- Use mensagens em português seguindo Conventional Commits.

Exemplos válidos:

- `feat: adiciona endpoint de jwks`
- `fix: corrige leitura de segredo no startup`
- `chore: incrementa versão para 1.0.14`
- `test: ajusta cobertura do caso de credenciais inválidas`

Prefira mensagens curtas, objetivas e diretamente relacionadas à alteração.

## Restrições Práticas

- Não quebre o fluxo atual baseado em Quarkus, Maven Wrapper e scripts da pasta `scripts/`.
- Não troque soluções nativas do framework por implementação manual sem justificativa técnica.
- Não ignore falhas simples de compilação, testes ou warnings fáceis de corrigir dentro do escopo da tarefa.
