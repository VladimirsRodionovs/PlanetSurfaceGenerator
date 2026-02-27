# Session Context (Detailed)
Date: 2026-02-14
Project: `/home/vladimirs/PlanetSurfaceGenerator/planet-generator`

## 1) Договоренности с пользователем (критично)
- Изменения в код вносить только после явного подтверждения пользователя.
- Детерминизм обязателен: все рандомы должны идти от основного seed.
- Фокус итерации: реки + климатическая физика + затем биомы.
- Для анализа использовать свежие дампы `debug_tiles_StarSystem_4_P4_obj5.tsv` и историю `_prev1/_prev2`.

## 2) Текущее состояние репозитория/среды
- В рабочей директории нет `.git` (git-команды недоступны как в репозитории).
- Компиляцию проверяем локально `javac` по измененным классам.
- Полный `mvn compile` в этой среде нестабилен из-за сетевого доступа к Maven Central.
- JavaFX-классы в песочнице могут не компилироваться целиком; логика ядра проверяется отдельно.

## 3) Главные изменения, которые сейчас АКТИВНЫ

### 3.1 Климат/ветер/влага (`WindGenerator`)
Файл: `src/main/java/org/planet/core/generation/WindGenerator.java`

- Ветровые пояса и их геометрия зависят от наклона планеты:
  - `hadleyEdge = 30 + min(10, |tilt|*0.2)`
  - `ferrelEdge = 60 + min(8, |tilt|*0.15)`
- Тропическая/умеренная зональность привязана к `hadleyEdge/ferrelEdge`, а не к жестким 15/24/28/62.
- Для `PHYSICAL` добавлен мягкий прокси глобальной конвекции на этапе АДВЕКЦИИ ВЛАГИ:
  - `moistureAdvectionVector(...)` добавляет плавный полюсный компонент переноса влаги,
  - сама карта ветра не меняется, меняется только перенос IWV.
- Для `PHYSICAL` слегка усилен дальний перенос влаги в субтропиках/шторм-треках:
  - `moistureTransportWindBoost(...)` возвращает `1.0..1.28`.

### 3.2 Временной шаг стал гораздо более инвариантным
Файл: `src/main/java/org/planet/core/generation/WindGenerator.java`

- `stepHours` теперь configurable через системное свойство:
  - `-Dplanet.climate.stepHours=<1..24>`
  - дефолт: `6`.
- `advSubSteps` теперь адаптивен:
  - `advSubSteps = round(stepHours / 1h)` (минимум 1),
  - `dtSub` стремится к ~1 часу.
- Добавлена нормализация "долей" по времени:
  - `scaleFractionByDt(fracRef, dtHours, refHours) = 1 - (1-fracRef)^(dt/ref)`.
- На эту нормализацию переведены:
  - crosswind mix и front-eddy mix в `advectIwvConservative(...)`,
  - почвенная диффузия (`diffuseSoilMoistureConservative`) через `soilDiffKappa`,
  - precipitation caps `capFracWater` и `capFrac` (land).
- Диурнальный цикл температуры интегрируется по интервалу шага:
  - `meanDiurnalCos(...)` вместо взятия только точечной фазы.

### 3.3 Усилено ночное охлаждение сухих тайлов
Файл: `src/main/java/org/planet/core/generation/WindGenerator.java`

Текущие коэффициенты:
- `dayAmp = amp * (0.84 + 0.18 * dryness)`
- `nightAmp = amp * (1.06 + 3.10 * dryness)`

Это усиливает ночной провал температуры при низкой влажности почвы.

### 3.4 Диагностика баланса почвы добавлена в модель и дамп
- Поля в `Tile`:
  - `soilStartDiag`, `soilEndDiag`,
  - `soilFromPrecipDiag`, `soilFromEvapDiag`, `soilFromDiffDiag`.
  Файл: `src/main/java/org/planet/core/model/Tile.java`

- Заполнение этих полей:
  Файл: `src/main/java/org/planet/core/generation/WindGenerator.java`

- Выгрузка в dump:
  - UI dump: `src/main/java/org/planet/app/Main.java`
  - Batch dump: `src/main/java/org/planet/app/BatchMain.java`
  - Новые колонки: `soilStart`, `soilEnd`, `soilFromPrecip`, `soilFromEvap`, `soilFromDiff`.

Важно:
- `soilStart` сейчас намеренно означает старт всего климатического прогона (не только последних суток),
  т.к. пользователь попросил вернуть именно так.

## 4) River model: что важно помнить
Файл: `src/main/java/org/planet/core/generation/RiverGenerator.java`

- Отбор истоков:
  - по потенциальному потоку (`riverPotentialKgS`),
  - top fraction сейчас `SOURCE_TOP_POTENTIAL_FLOW_FRACTION = 0.20` (20%),
  - минимум высоты `SOURCE_MIN_ELEVATION = 2`.
- Стартовый порог/масштаб маршрутов и расхода:
  - `RIVER_START_KG_S = 480_000` (480 т/с),
  - routing + runoff дают `baseFlowKgS`/`runoffFlowKgS`.
- Обмен "река <-> почва" работает двусторонне:
  - `localMoistureExchangeKgS(...)`
  - `applyExchangeToSoilMoisture(...)`
  - `exchange < 0` = река отдает влагу в почву,
  - `exchange > 0` = тайл подпитывает реку.

Формулы обмена:
- `surplus = clamp((soil - 55)/45, 0..1)`
- `deficit = clamp((40 - soil)/40, 0..1)`
- `gain = (0.58*baseFlow + 0.12*qIn) * surplus`
- `loss = (0.12*qIn + 0.05*baseFlow) * deficit`
- `delta = gain - loss`, потом clamp диапазоном `[-0.45*qIn, max(1.15*baseFlow, 0.55*qIn)]`.

## 5) Биомы (актуальная конфигурация)
Файл: `src/main/java/org/planet/core/generation/BiomeGeneratorV2.java`

- Вклад гидрофактора смещен в сторону почвы:
  - `hydro = peNorm*0.35 + soilNorm*0.65`
- Пустынный порог:
  - `moistAdj < -15` -> пустыня.
- Для пустынных низменностей введено детерминированное смещение типов:
  - ~20% песчаная, ~80% каменистая.

## 6) Что пробовали и откатили
- Попытка двухслойной глобальной конвекции (`iwvHigh`) была внедрена и затем полностью откатана,
  потому что дала поломку поведения (в т.ч. видимые артефакты по evap/runoff).
- Текущий вариант глобальной конвекции реализован мягко только через транспорт влаги в `PHYSICAL`
  (без отдельного верхнего резервуара).

## 7) Скрипт для сравнения шага времени
Файл: `scripts/compare_timestep_metrics.sh`

Назначение:
- сравнить два dump-файла (например 1ч vs 6ч),
- вывести глобальные и широтные метрики:
  - `P`, `E`, `Soil`, `Atm`,
  - относительную дельту `%`.

Пример:
```bash
./scripts/compare_timestep_metrics.sh dump_step1h.tsv dump_step6h.tsv
```

## 8) Последние наблюдения по дампам (суть)

### 8.1 Тайл 2013
- Был кейс: очень низкая почва при наличии русла.
- Разбор показал:
  - река на сегменте отдавать влагу может,
  - но итог мог быть сухим из-за нулевых осадков, сухих соседей и последующего климатического пересчета.

### 8.2 Тайл 8140
- Ранее: `P=0`, `E~9`, почва выглядела "слишком высокой".
- После новых правок и свежего дампа:
  - `surface=SAVANNA`,
  - `P=11.6277`, `E=4.8160`,
  - `soil 41.37 -> 45.70`,
  - `+prec=12.7613`, `-evap=8.6688`, `+diff=1.6795`.
- То есть в текущем прогоне тайл уже увлажняется осадками и диффузией, а не "из ниоткуда".

## 9) Ключевые открытые вопросы (на следующую сессию)
- Дотюнить полосу `30–45°`, чтобы увлажнение было стабильнее, не пересушивая 15–30°.
- Проверить step-invariance строго на одном и том же seed и режиме:
  - два прогона `stepHours=1` и `stepHours=6`,
  - сравнение скриптом `compare_timestep_metrics.sh`.
- Уточнить интерпретацию `soilStart` (старт прогона vs старт диагностических суток) под задачу анализа.

## 10) Быстрый чеклист продолжения
1. Сгенерировать два дампа (1ч/6ч) одного и того же объекта.
2. Прогнать `scripts/compare_timestep_metrics.sh`.
3. Снять дельты по зонам.
4. Если дельты > целевого порога, трогать только dt-нормализацию, не меняя физические коэффициенты по сути.

## 11) Команды, полезные для диагностики
```bash
# Компиляция ядра климата
javac -cp src/main/java src/main/java/org/planet/core/generation/WindGenerator.java

# Найти тайл в дампе
awk -F'\t' 'NR>44 && $1==8140 {print}' debug_tiles_StarSystem_4_P4_obj5.tsv

# Сравнить 1ч и 6ч дампы
./scripts/compare_timestep_metrics.sh dump_1h.tsv dump_6h.tsv
```

## 12) Важные ограничения процесса
- Перед любыми правками спрашивать подтверждение пользователя.
- Не делать "самодеятельных" изменений.
- Любую спорную правку сначала описывать кратко и только после `да` применять.

