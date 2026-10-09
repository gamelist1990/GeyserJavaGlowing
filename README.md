# GeyserJavaGlowing 0.2.0

Java EditionのプレイヤーのGlowingを、Geyserの追加エンティティプロパティと同梱リソースパックでBedrockへ同期する拡張機能です。Javaのスコアボードチーム色も反映します。

## ビルドと導入

Java 21で実行します。Gradle Wrapperを同梱しています。リソースパック検証にはPython 3も必要です。

```bash
./gradlew --no-daemon clean build testResourcePack
```

生成物は `build/libs/GeyserJavaGlowing-0.2.0.jar` です。Geyserの `extensions` フォルダへ置いて再起動し、Bedrock側で配信されるリソースパックを適用してください。PaperプラグインではなくGeyser拡張です。Behavior Packは不要です。

Geyser `2.11.3-SNAPSHOT` のAPI/Coreを使ってビルドしています。起動ログにパック登録と `Installed glowing hooks for 6 Java packet translators.` が出ることを確認してください。

Javaサーバーでの操作例:

```text
/effect give <player> minecraft:glowing 60 0 true
/effect clear <player> minecraft:glowing
/team add glowRed
/team modify glowRed color red
/team join glowRed <player>
```

## 同期方式

既存のJavaパケット変換器を呼び出した直後、同じセッションのイベントループでGlowingフラグ `0x40` とチーム情報を処理します。接続後のリスナー登録待ちや定期ポーリングがないため、初期メタデータも対象になります。Geyser本体のJAR・ソースは変更しません。

`minecraft:player` に整数 `glow:color` と小数 `glow:r/g/b/a` を登録し、`PlayerEntity.updatePropertiesBatched` で同期します。状態は観測者ごとに保持し、同じ状態の重複送信を避けます。生成・削除・再ログイン・リスポーン時には古い発光状態を掃除します。`/playanimation` とパーティクルは使用しません。

RPは `Glowing Player Outline RP` からビルド時にmcpackへ生成してJARに同梱します。元パックは **Glowing Player Outline V25.0.1.3 / BigGamers4u** です。元のクレジットを残しています。

## 検証

Java単体テスト17件とリソースパック検証4件を実装しました。パケット変換順序、初期メタデータ、発光ON/OFF、チーム色、チーム離脱、プレイヤー再生成、ID再利用、リスポーン、観測者間の状態分離を確認します。

実サーバーとの検証には [PrismarineJS/bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) を使います。再実行方法と通信上のテスト用設定は [integration/README.md](integration/README.md) を参照してください。最終結果の `summary.json` と各クライアントのJSONLパケットログを成果物に含めます。

## 対応範囲

- 対象はプレイヤーです。Mobの輪郭には対応しません。
- 実サーバー検証はGeyser StandaloneとPaperの組み合わせです。Paper内のGeyser-Spigot構成や他の拡張との組み合わせは未検証です。
- ヘッドレスクライアントで同期パケットとRP配信を確認します。実機での輪郭描画、壁越し表現、スリムスキン・防具・他RPとの併用は別途目視確認が必要です。
- Geyser内部のパケット変換器を使うため、Geyser更新時には再ビルドと再検証が必要になる場合があります。
- RPはプレイヤーのエンティティ定義・マテリアルを変更するため、他のプレイヤー描画RPと競合する場合があります。

## Geyser Cooldown 1.3 との併用

両方の元パックがプレイヤー定義を上書きするため、そのままの併用では片方の機能が消える場合があります。
ローカルの Cooldown 1.3 JAR から、両パックの優先順に依存しない互換パックと互換 JAR を生成できます。
生成・導入・検証の手順は [COOLDOWN_COMPATIBILITY.md](COOLDOWN_COMPATIBILITY.md) を参照してください。
