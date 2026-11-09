# Casa-Controle

App Android nativo que liga e desliga o PC (Wake-on-LAN) e controla a TV LG pela rede local, com pareamento seguro. Projeto pessoal, feito para o meu Galaxy S23 na rede de casa.

## O que faz

- **PC:** Wake-on-LAN para ligar, desligamento normal com 15 segundos para cancelar, estado automático (ligado / desligado / sem conexão) e um botão para abrir o AnyDesk no PC.
- **TV LG:** liga, desliga, lê o estado e funciona como controle remoto completo (setas, OK, Home, voltar, ajustes, volume, reprodução e digitação), pelo protocolo SSAP da webOS.
- **IP que muda:** o roteador usa DHCP e troca os IPs. Quando um aparelho para de responder no endereço salvo, o app procura na sub-rede do Wi-Fi quem atende na porta certa **com o mesmo certificado já fixado** e atualiza o IP sozinho, sem perder o pareamento.

## Como foi pensada a segurança

Esta é a parte que achei mais interessante de resolver, porque o app liga/desliga e controla máquinas na rede:

- **Agente do PC** (um serviço Python que roda com usuário limitado): escuta em HTTPS na porta 8765. A regra de firewall só aceita a sub-rede local (`RemoteAddress LocalSubnet`), então mesmo com o bind aberto o acesso fica restrito à casa.
- **Certificado fixado (cert pinning):** o celular guarda o certificado do agente e da TV. É isso que permite reencontrar o aparelho quando o IP muda sem risco de falar com a máquina errada.
- **Pareamento por token:** um código de 6 dígitos gera um token aleatório (`secrets.token_urlsafe`); só o hash do token fica salvo no PC, nunca o token em si. Sem pareamento, nenhum comando é aceito.
- **Sem abrir nada na internet:** não precisa de redirecionamento de porta no roteador nem de nuvem. Tudo acontece na rede local.
- O agente **não executa comandos arbitrários** nem registra tokens, corpos de requisição ou códigos de pareamento nos logs.

## Estrutura

| Pasta | O que tem |
|---|---|
| `app/` | App Android (Java, sem bibliotecas de UI — tudo em código). |
| `pc/` | Agente do PC em Python (`agente.py`), o controle do AnyDesk e os scripts de instalar/atualizar/desinstalar (PowerShell). |
| `tests/` | Teste do pacote mágico de Wake-on-LAN. |
| `pc/test_agente.py` | Testes do agente: pareamento, autenticação por token, recusa de comando não pareado. |

## Observações

- **AnyDesk** é um programa de terceiros. O app apenas abre o AnyDesk que já está instalado no PC; a conexão usa a autorização configurada no próprio AnyDesk.
- Os endereços MAC e IPs no código são exemplos (`AA:BB:CC:DD:EE:01` etc.); cada um configura os seus nos Ajustes do app.
- Para compilar o app é preciso o Android SDK. O agente do PC roda com Python 3.

## Licença

[MIT](LICENSE).
