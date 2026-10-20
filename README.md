# Launcher estilo Apple TV para Android TV

Tela inicial simples e leve: fundo preto, dock com 4 apps, grade com os demais apps, relógio, saudação e botões de Wi-Fi e Configurações. Feito em Java puro, sem bibliotecas externas e sem rede.

Feito para um MECOOL KD5 (Android 14), mas serve para qualquer Android TV/Google TV.

## Como gerar o APK (pelo GitHub, sem instalar nada no PC)

1. Crie um repositório no GitHub (pode ser privado) e envie o conteúdo desta pasta (Add file > Upload files).
2. Se a pasta oculta `.github` não subir, vá em Add file > Create new file, digite o nome `.github/workflows/build.yml` e cole o conteúdo do arquivo `build.yml` que acompanha este projeto.
3. Abra a aba **Actions**. O build roda sozinho a cada envio (ou clique em "Build launcher APK" > Run workflow). Leva uns 3 a 5 minutos.
4. Abra a execução concluída, role até **Artifacts** e baixe `launcher-apk`. Extraia o zip: o arquivo é `launcher-debug.apk`.
5. Coloque o `launcher-debug.apk` em `C:\platform-tools` e rode `instalar.ps1`.

Se o build falhar, abra a execução, copie a mensagem de erro em vermelho e mande para o Claude.

## Instalar e testar

```
powershell -ExecutionPolicy Bypass -File .\instalar.ps1
```

O script instala, define o launcher como tela inicial e abre a tela. Ele **não desativa o launcher do Google**: se algo der errado, o Google continua ali.

Quando estiver tudo certo, desative o launcher original:

```
powershell -ExecutionPolicy Bypass -File .\desativar.ps1 com.google.android.tvlauncher
```

## Voltar ao launcher do Google

```
.\adb shell pm enable --user 0 com.google.android.tvlauncher
.\adb shell cmd package set-home-activity com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity
.\adb shell pm uninstall dev.tvlauncher.home
```

## Personalizar

Tudo fica em `launcher/src/main/res/values/config.xml`:

- **Ordem e apps do dock:** edite a lista `dock_packages` (nomes de pacote, da esquerda para a direita).
- **Esconder apps da grade:** adicione o pacote em `hidden_packages`.
- **Nome na saudação:** preencha `greeting_name`.
- **Cor de fundo:** `launcher_background` (padrão `#000000`, preto puro). Não há imagem de papel de parede, para manter o launcher leve.

Depois de editar, envie o arquivo de novo ao GitHub e baixe o novo APK. Como o APK de debug é assinado com uma chave diferente a cada build, o `instalar.ps1` desinstala a versão anterior automaticamente se for preciso.

## Controle remoto

- Cima/baixo andam exatamente uma linha. Cima no dock vai para a barra do topo; cima na primeira linha da grade volta ao dock.
- As pontas da barra do topo não "vazam" para os apps.
- Voltar leva ao dock. O launcher lembra o último item selecionado.

## Entradas (HDMI / AV / TV ao vivo)

O botão "Entradas" só aparece se o aparelho tiver entradas. Boxes e sticks (como o KD5) normalmente não têm, então o botão fica escondido.

## Fonte

O relógio usa a fonte Outfit (licença SIL Open Font License), baixada automaticamente pelo GitHub durante o build. Se o download falhar, o launcher usa a fonte fina do sistema.

## Ao terminar

Desligue a depuração USB e a depuração de rede no aparelho, reinicie e confirme que `adb connect IP:5555` não conecta mais.
