# Glowing / Geyser Cooldown 1.3 互換版

対象はローカルの **Geyser Cooldown Crossplay Weapons 1.3.0** と GeyserJavaGlowing 0.2.0 です。
Cooldown 開発フォルダーの 1.1.0 ソースとは別の、実際にサーバーへ導入されている JAR を入力にします。
作成済みの互換 JAR・mcpack とチェックサムは [compatibility/glowing-0.2.0-cooldown-1.3.0](compatibility/glowing-0.2.0-cooldown-1.3.0) に保存しています。

## 競合と対応

両方の元パックが `entity/player.entity.json` を定義します。
Cooldown が優先されると発光用 material・geometry・render controller が消え、
Glowing が優先されると Cooldown の `gca_*` 変数と recovery controller が消えます。

互換版では両方のパックに同じ統合済みプレイヤー定義と必要な描画素材を同梱します。
これにより、この2つのパックのどちらが優先されても統合した定義が残ります。

- Glowing の発光プロパティ・色、slim/wide 判定、輪郭、防具、ケープ、エリトラを保持。
- Cooldown の500種類の20ms通知、サーバー指定時間、回復 controller、
  剣・斧・メイス・素手・ツルハシ・シャベル・槍・トライデントの元定義を保持。
- Glowing に残っていた Blurry 用の固定時間 controller の自動実行を外し、
  Cooldown のサーバー通知による回復へ一本化。既存の animation alias 自体は保持。
- 両方の元パックの UUID は維持し、RP の patch version を1増やしてキャッシュ更新。
- 互換 JAR の変更は同梱 mcpack のみ。拡張の ID・バージョン・Java class は変更しない。

## 生成

Python 3 とビルド済みの Glowing JAR、ユーザー所有の Cooldown 1.3 JAR が必要です。
外部パックをダウンロードせず、指定したローカルの素材から生成します。

```bash
bash ./gradlew --no-daemon build testResourcePack
python3 scripts/build_cooldown_compat.py \
  --cooldown-input /absolute/path/GeyserCooldownAnimation-1.3.0.jar \
  --glowing-jar build/libs/GeyserJavaGlowing-0.2.0.jar
```

出力先は `build/cooldown-compat/` です。

| ファイル | 用途 |
| --- | --- |
| GeyserJavaGlowing-0.2.0-cooldown-compat.jar | 発光用拡張の互換版 |
| GeyserCooldownAnimation-1.3.0-glowing-compat.jar | Cooldown 拡張の互換版 |
| GeyserJavaGlowing-cooldown-compat.mcpack | 発光側のパック単体 |
| GeyserCooldownAnimation-glowing-compat.mcpack | Cooldown 側のパック単体 |
| compatibility-report.json / SHA256SUMS.txt | 入力と成果物のハッシュ |

元 JAR・元リソースパックと開発ソースは上書きしません。
素材を更新するときは、その版を入力にして再生成・検証してください。
未知の alias やファイルの競合は自動上書きせず、生成を失敗させます。

## 導入

1. 既存 JAR をバックアップして、Geyser の `extensions/` で両方を互換 JAR に置き換える。
   同じ拡張 ID の元 JAR と互換 JAR を同時に置かない。
2. 元版の単独 mcpack を手動配置している場合も、対応する互換 mcpack に置き換える。
   通常は拡張 JAR がパックを展開・登録するため、単独パックの追加は不要。
3. Geyser を再起動し、Bedrock でサーバーパックを再取得する。
4. 発光 ON/OFF とチーム色、剣・斧・メイス等の攻撃と持ち替え、一人称・三人称を確認する。

この生成作業は稼働サーバーの JAR 差し替えや再起動を行いません。

## 検証

実際の入力 JAR と生成した2つのパックを使って検証します。

```bash
COOLDOWN_COMPAT_INPUT=/absolute/path/GeyserCooldownAnimation-1.3.0.jar \
GLOWING_COMPAT_JAR=$PWD/build/libs/GeyserJavaGlowing-0.2.0.jar \
python3 -m unittest discover -s tests -v
```

検証内容は両優先順の有効プレイヤー定義、controller の alias 解決、
JSON とパス制限、500種類の通知と武器素材の保持、発光素材の保持、
RP の UUID・cache version、JAR の非RPエントリの完全一致、成果物ハッシュです。
入力パスを指定しない通常のテストでは、ローカル素材に依存する検証だけを skip します。

これはパック構造と合成定義の検証です。Bedrock 実機での描画・timeline の実行は別途確認が必要です。
BerryPVP 等の第3のパックが同じ player 定義を上書きする組み合わせは、この2つの優先順検証には含みません。

## クレジット

Glowing Player Outline の BigGamers4u、Cooldown パック内の作者・参考元の表示を保持します。
Cooldown の `THIRD_PARTY_NOTICES.md`・`LICENSE`・`licenses/` を両方の互換パックへ同梱します。
互換化によって元素材のライセンスは変更されません。
