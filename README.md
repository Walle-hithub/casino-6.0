# Casino (Spigot 26.3) - fichas de poker, tragaperras, ruleta y blackjack
Requisitos: Java 25. No necesita Vault. Si vienes de una version anterior, BORRA plugins/Casino/config.yml.

## Compilar
    mvn clean package        (o usa la accion de GitHub en .github/workflows)
El .jar queda en target/Casino.jar -> copialo a plugins/.

## Fichas
La moneda del casino es la "Ficha de Poker" (un item). Se consigue en la CAJA DE CAMBIO
(minerales <-> fichas) o con /casino dar <jugador> <cantidad> (admin).

## Maquinas fisicas (admin)
Ponte en una zona plana MIRANDO hacia donde construir:
    /casino construir tragaperras | ruleta | blackjack | cambio | tesoro
Al interactuar (palanca, bloque de color, mesa, mostrador) el jugador elige su apuesta en un menu.

## Carteles (admin)
[Casino] / slots|moneda|dados|blackjack|cambio

## Comandos de jugador
/casino cambio | /casino fichas
/casino slots|dados|blackjack [cantidad]   (sin cantidad abre el menu)
/casino coinflip <cantidad> <cara|cruz>
Admin: /casino construir <tipo>, /casino dar <jugador> <cantidad>, /casino reload

## Maquina del Tesoro (premios en items)
/casino construir tesoro -> maquina morada con palanca y un BOTON a la izquierda.
Cada giro cuesta fichas (treasure.cost) y siempre da un premio real (minerales, manzanas de oro,
libros encantados, botellas de XP...). Los premios, su valor y su probabilidad se editan en config.yml
(treasure.rewards). Los jugadores ven la tabla con /casino premios o pulsando el boton de la maquina.
