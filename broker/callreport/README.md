# Call Report Mobile

Тази папка е предвидена за новата mobile call report функционалност, която ще работи с Android приложение `calllog.apk` и със server-driven HTML форма.

## Цел

Идеята е брокерът да има Android приложение, което:

1. Следи входящи и изходящи обаждания по Android-съвместим начин за Android 10+.
2. Показва native notification / caller info card с данни, изтеглени от сървъра по телефонен номер.
3. Има постоянен бутон `Отвори`, който отваря приложение с `WebView`.
4. Зарежда HTML форма от сървъра, за да може формата да се променя без нов build на `.apk`.
5. Изпраща данни към сървъра, който ги записва в call log.

## Какво остава native в Android app-а

Native частта в `calllog.apk` трябва да съдържа само нещата, които уеб страница не може да прави сама:

- интеграция с Android call events
- permissions / roles
- notification UI
- бутон за отваряне на формата
- `WebView` wrapper за remote HTML страницата
- локални настройки, например `Base URL`

Самият notification не трябва да е `WebView`. Той трябва да е native Android notification с предварително дефиниран layout, който се пълни с данни от сървъра.

## Какво остава server-driven

Сървърът трябва да управлява:

- lookup по телефонен номер
- текста и съдържанието, което се показва в notification-а
- URL за отваряне на HTML формата
- самата HTML форма
- submit endpoint-а
- логиката за запис в call log

Това позволява:

- да се обогатява caller info без нов `.apk`
- да се променя формата без нов `.apk`
- да се променя submit логиката от PHP

## Android app настройки

`calllog.apk` трябва да има поне:

- поле за `Base URL`
- евентуално token / ключ за достъп
- runtime искане на нужните разрешения
- настройка за Android role, ако е нужна за caller ID / screening
