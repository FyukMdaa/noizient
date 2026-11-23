# noizient

FocusとSleepの環境音をオンデバイスで合成するAndroidアプリです。Android 8.1（API 27）以降に対応する構成で、ネットワーク権限は使いません。

## APK

GitHub Actionsの **Android APK** は push、pull request、手動実行で起動し、DSPのスモークテスト、デバッグAPKのビルド、Android Lintを実行します。成功した実行の **Artifacts → noizient-debug** に `app-debug.apk` が入ります。

ローカルでビルドする場合は JDK 17、Gradle 8.9、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1 を使用します。

```sh
gradle :app:assembleDebug :app:lintDebug
```

## 操作

- FocusまたはSleepを選び、再生します。配合・音量・追加レイヤーは再生中も変更できます。Sleepの導入時間は次の再生開始時に適用します。
- 終了タイマーはサンプル数で計測し、最後の20秒でフェードアウトします。通知から一時停止・再開・停止できます。
- 「好き」「苦手」は即時保存され、履歴から理由やメモを追記できます。JSONには設定、評価、各評価時点の直近イベントが含まれます。
- 音声出力が切断されたときはプレイヤーから再開できます。

## 実装

- Kotlinの画面はKarutaUIの平面的なカード、状態が分かる操作ラベル、ノブのない標準SeekBarを使用します。広幅端末では追加レイヤーを右側に表示します。
- 音声はOboe/AAudioのfloatステレオストリームで生成します。`assets/focus.bin` と `assets/sleep.bin` は、添付HTMLの周波数・位相・帯域メタデータをリトルエンディアンのfloat64で並べ、続けてbase64から展開したuint8のプロファイルを格納しています。
- ホストテストは44.1/48 kHzの両レートでレンダリングし、データ寸法、ループ位置、同じシードでの再現性、追加レイヤー無効時の出力を確認します。
