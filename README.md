# 🐉 Agente Garimpeiro

[![Build](https://github.com/guilhermecesars/agente-garimpeiro/actions/workflows/build.yml/badge.svg)](https://github.com/guilhermecesars/agente-garimpeiro/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

Ferramenta de **pesquisa refinada de produtos** em marketplaces chineses (Taobao),
pensada para quem compra para revenda e precisa filtrar rápido por preço e volume
de vendas. Backend em **Java puro** (sem framework) servindo uma API HTTP e um
front-end web leve.

> Projeto de estudo/portfólio. O fluxo de acesso pago (cadastro → login → checkout)
> é **simulado em memória** para demonstrar a mecânica — não há cobrança nem
> gateway de pagamento real.

## ✨ O que faz

- Busca produtos por categoria com filtros de **preço mínimo/máximo** e **vendas mínimas**.
- Consome a API do Taobao via **RapidAPI** e normaliza o resultado (preço, imagem, vendas, link).
- Gera links de compra assistida (Superbuy) a partir do item encontrado.
- **Cache em memória (6 h)** para a mesma busca, reduzindo chamadas à API.
- Área de acesso com cadastro/login e um checkout **simulado** que libera a busca.

## 🛠️ Tecnologias

| Camada    | Tecnologia                                             |
| --------- | ------------------------------------------------------ |
| Backend   | Java 21, `com.sun.net.httpserver` (HTTP server nativo) |
| Frontend  | HTML, CSS e JavaScript (vanilla, sem build)            |
| Integração| RapidAPI (Taobao Datahub)                              |
| Deploy    | Docker · Render                                        |

## 🚀 Como rodar localmente

Pré-requisitos: **JDK 21+** e uma chave da RapidAPI.

A aplicação lê a chave da variável de ambiente `RAPIDAPI_KEY`.

**Windows** (há um atalho pronto):

```bat
copy config.local.bat.example config.local.bat
REM edite config.local.bat e coloque sua RAPIDAPI_KEY
AgenteGarimpo.bat
```

**Linux/macOS:**

```bash
export RAPIDAPI_KEY="sua_chave_aqui"
javac -encoding UTF-8 -d bin src/com/garimpo/*.java
java -cp bin com.garimpo.Main
```

Depois acesse <http://localhost:8081>.

### Com Docker

```bash
docker build -t garimpeiro .
docker run -e RAPIDAPI_KEY="sua_chave_aqui" -p 8081:8081 garimpeiro
```

## 📁 Estrutura

```
src/com/garimpo/
  Main.java          # servidor HTTP, rotas e integração com a RapidAPI
  AuthService.java   # validação do token de acesso
  UserStore.java     # cadastro, login e checkout simulado (em memória)
public/              # front-end (index.html, app.js, style.css)
Dockerfile           # imagem para deploy
render.yaml          # configuração do deploy no Render
```

## 🔐 Segurança

- A chave da RapidAPI **nunca** fica no código: vem da variável de ambiente
  (`config.local.bat` está no `.gitignore`).
- O servidor de arquivos estáticos valida o caminho pedido e bloqueia
  *path traversal* (tentativas de ler arquivos fora da pasta `public/`).

## 💡 O que aprendi neste projeto

- Subir um servidor HTTP e tratar rotas/JSON em Java sem framework.
- Consumir uma API externa autenticada e tratar erros e limites de uso.
- Reduzir chamadas repetidas com cache em memória e TTL.
- Empacotar e publicar a aplicação com Docker no Render.

## 📄 Licença

[MIT](LICENSE) © Guilherme Cesar
