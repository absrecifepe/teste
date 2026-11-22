# ASCII Typer

App Android que transforma uma foto em um **vídeo MP4 em que a imagem vai sendo "digitada"
em caracteres ASCII**, como num terminal, com cursor piscando.

## Funcionalidades

- Escolhe foto pelo seletor do sistema (sem pedir permissão de armazenamento) ou recebe via
  "Compartilhar" da galeria.
- Ajustes: nível de detalhe (30–160 colunas), duração da digitação (3–30 s), 5 estilos de cor
  (Branco — padrão, Matrix, Terminal, Colorido, Âmbar), 4 conjuntos de caracteres (Clássico, Detalhado,
  Blocos, Binário) e inversão claro/escuro.
- **Zoom de abertura:** o vídeo começa com a câmera bem perto das letras sendo digitadas, seguindo
  o cursor; depois de algumas linhas a digitação acelera e a câmera se afasta até mostrar a foto toda.
- **Texto que forma a imagem (opcional, até 80 caracteres):** em vez dos símbolos ASCII, a foto é
  desenhada com as letras do texto, repetido em sequência; com o zoom, a 1ª repetição é digitada de perto.
- Pré-visualização instantânea do resultado final.
- Gera MP4 H.264 a 30 fps direto no aparelho (MediaCodec, sem bibliotecas externas), com até 1080 px de largura.
- Salva em `Filmes/ASCII Typer` ou compartilha direto (WhatsApp, Instagram, TikTok…).
- Tudo é processado no próprio celular: nenhuma foto sai do aparelho.
- Sem anúncios, sem internet e sem permissões especiais.

## Estrutura

| Arquivo | O que faz |
|---|---|
| `ascii/AsciiConverter.kt` | Foto → grade de caracteres (auto-contraste, rampas de caracteres) |
| `ascii/AsciiRenderer.kt` | Desenha os quadros (digitação incremental + cursor) e a linha do tempo |
| `ascii/VideoExporter.kt` | Converte quadros para YUV e codifica em MP4 |
| `MainViewModel.kt` | Estado, pré-visualização, exportação, salvar/compartilhar |
| `ui/MainScreen.kt` | Interface em Jetpack Compose |

Requisitos: Android 10+ (minSdk 29), targetSdk 36.

## Compilar

**Pelo Android Studio:** abra a pasta do projeto e clique em Run.

**Pela linha de comando:** `./gradlew assembleDebug` → APK em `app/build/outputs/apk/debug/`.

**Pelo GitHub:** cada push roda o workflow `.github/workflows/android.yml`, que gera o APK de
debug como artefato (aba *Actions* → execução → *Artifacts*).

## Distribuir o APK por conta própria (sem Play Store)

1. **Assinatura (uma vez só):** cadastre em *Settings → Secrets and variables → Actions* os
   secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD` da sua chave.
   Guarde a chave (`.jks`) e a senha em lugar seguro: sem elas, novas versões não instalam por
   cima das antigas.
2. **Gerar uma versão:** aumente `versionCode`/`versionName` em `app/build.gradle.kts` e crie uma
   tag, ex. `v1.0.0`. O GitHub publica um *Release* com o `ASCII-Typer.apk` para download.
   (Sem tag, o APK assinado fica em *Actions → execução → Artifacts → app-release-apk*.)
3. **Instalar:** quem baixar precisa permitir "Instalar apps desconhecidos" para o navegador ou
   gerenciador de arquivos. O Play Protect pode mostrar um aviso de "app desconhecido" — é normal
   para apps fora da loja; basta tocar em "Instalar mesmo assim".

## Publicar na Play Store

1. **Troque o `applicationId`** em `app/build.gradle.kts` (ex.: `com.seunome.asciityper`).
   Ele é permanente depois de publicado. Se quiser, troque também o nome em `res/values/strings.xml`.
2. **Crie a chave de assinatura** (guarde o arquivo e as senhas em local seguro — sem ela você
   não consegue atualizar o app):
   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```
3. **Configure a assinatura**, de um destes jeitos:
   - Local: crie `keystore.properties` na raiz (já está no `.gitignore`):
     ```properties
     storeFile=release.jks
     storePassword=SUA_SENHA
     keyAlias=upload
     keyPassword=SUA_SENHA
     ```
     e rode `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
   - GitHub: em *Settings → Secrets and variables → Actions* crie `KEYSTORE_BASE64`
     (saída de `base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.
     O workflow passa a gerar o `.aab` assinado como artefato.
4. **Conta de desenvolvedor**: crie em <https://play.google.com/console> (taxa única de US$ 25).
   Contas pessoais novas precisam fazer um **teste fechado com pelo menos 12 testadores por 14 dias**
   antes de liberar a produção.
5. **Ficha da loja**: textos prontos em [`store/listing.md`](store/listing.md); ícone 512×512 e
   imagem de destaque 1024×500 em `store/`. Faltam as capturas de tela (tire do app rodando).
6. **Política de privacidade**: publique [`PRIVACY.md`](PRIVACY.md) num link público (ex.: GitHub
   Pages) e informe a URL no Console. Em *Segurança dos dados*, declare que o app **não coleta
   nem compartilha dados**.
7. Envie o `.aab`, preencha a classificação de conteúdo e o público-alvo, e envie para revisão.
   A cada nova versão, aumente `versionCode` e `versionName` em `app/build.gradle.kts`.
