# Offline統合テスト

実際のPaperとGeyser Standaloneを起動し、`bedrock-protocol` のヘッドレスクライアント3人を同時接続します。GlowCは途中で再接続するため、パケットログは4セッション分です。

## 使用した構成

| 項目 | バージョン |
| --- | --- |
| Paper / Javaサーバー | 26.2 build 132 / Java 25 |
| Geyser Standalone | 2.11.3 build 1249 / Java 21 |
| bedrock-protocol | 3.60.1（package-lock.jsonに固定） |
| GlowA / GlowC | Bedrock 1.26.30、protocol 1001 |
| GlowB | Bedrock 1.26.51、protocol 2193 |

PaperはGeyserのJavaプロトコル26.2と合わせています。全接続先は127.0.0.1です。offlineで接続するため、テスト用Geyser設定ではBedrockログイン認証の検証を無効にしています。

## 再実行

Java 21のJDK、Java 25、Node.js（今回24.19.0）、Python 3を用意してください。以下の公式ファイルをダウンロードし、独立したテスト用ディレクトリに置きます。Paperの初回起動はMojangサーバー本体・ライブラリのダウンロードが必要です。

- [Paper 26.2-132](https://fill-data.papermc.io/v1/objects/5ab560a769c1ab413cb7f637dd0dc697974571f2db0a667cfbac511422e51b26/paper-26.2-132.jar) → `<paper>/paper.jar`
- [Geyser 2.11.3-b1249](https://download.geysermc.org/v2/projects/geyser/versions/2.11.3/builds/1249/downloads/standalone) → `<geyser>/Geyser-Standalone.jar`

Paper側に `paper-server.example.properties` を `server.properties` としてコピーし、RCONパスワードをローカルでランダム値へ変更してください。Minecraft EULAに同意する場合はテスト用Paperディレクトリに `eula.txt`（`eula=true`）を用意します。

Geyser側では初回起動で生成されるconfig.ymlを使い、example.ymlと同じ項目へ変更してください。既存の本番ディレクトリは使わず、他の拡張・RPを入れない状態で検証します。

```bash
# プロジェクトのルートで
./gradlew --no-daemon clean build testResourcePack
cd integration
npm ci --ignore-scripts
TEST_PAPER_DIR=/absolute/path/to/paper \
TEST_GEYSER_DIR=/absolute/path/to/geyser \
TEST_JAVA25=/absolute/path/to/java25/bin/java \
TEST_JAVA21=/absolute/path/to/java21/bin/java \
npm test
```

スクリプトは現在ビルドした拡張JARをテスト用Geyserへ配置し、起動・コマンド実行・接続・終了を行います。`classes` はJava 21のjavacで自動生成します。サーバーとクライアントを同じスクリプト内で起動するため、プロセスごとにネットワークが分離される実行環境でも接続できます。

## 確認内容と出力

- 3人が同時にログイン・スポーンし、互いのプレイヤーを受信する。
- 全員に `glow:color/r/g/b/a` が定義される。
- RPをチャンクで実ダウンロードし、通知SHA-256およびビルドしたmcpackのバイト列と一致する。
- 白色発光、赤色発光、発光中の青色への変更が全観測者に届く。
- 別プレイヤーの解除で他人の発光が消えない。非発光の人が勝手に発光しない。
- チーム離脱で白色に戻る。後から再接続した観測者にも既存の発光状態が届く。
- 4回のON/OFF切替と最終解除が反映される。クライアントのパケット処理エラーがない。

`results/summary.json` に20件のチェック、実行コマンド、各接続のパケット数、RPのハッシュ、サーバー・拡張JARのハッシュが入ります。各クライアントの `*-packets.jsonl` には主要な受信パケットを時刻・段階付きで記録します。サーバーログも残します。RPの巨大なチャンク本体はJSONLへ保存せず、ハッシュで検証します。

## テスト用の通信調整

`bedrock-protocol` の通常の `createClient` はRPをダウンロードせずに完了応答を送るため、テストではその応答を置き換えて実ダウンロードします。またofflineの既定XUIDは全員0なので、各テストプレイヤーへ異なる架空IDを設定します。

`jsp-raknet` 2.1.3のRakNetバージョン10をテスト時だけ11へ変更し、Geyserを `-DGeyser.RakSendCookie=false` と `-DdisableNativeEventLoop=true` で起動します。RP情報のバイナリUUIDはライブラリ側で2つのlittle-endian longが逆順に表示されるため、要求にはGeyserが同時に送る文字列の `content_identity` を使います。Minecraftの発光パケットは変更しません。

この環境ではJavaからMojangの公開discovery/OpenID metadataの取得ができなかったため、`OfflineGeyserBootstrap` が同梱の公開JSON2件だけをJava標準のHTTPレスポンスキャッシュとして返します。取得元は次の公式URLです。Geyser本体のバイナリ、Minecraftパケット、暗号通信処理は変更しません。

- https://client.discovery.minecraft-services.net/api/v1.0/discovery/MinecraftPE/builds/1.0.0.0
- https://authorization.franchise.minecraft-services.net/.well-known/openid-configuration

offline設定による認証無効の警告、ネット未接続によるロケール・更新確認の失敗、コンテナ内のPaper OS情報取得警告がログに残ります。これらは上記パケットチェックと区別しています。

これはヘッドレスでの通信検証です。実機での輪郭描画やXbox認証付きログイン、NetherNet接続、他プラグインとの併用、長時間負荷は未検証です。
