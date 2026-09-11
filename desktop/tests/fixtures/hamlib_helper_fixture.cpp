#include <QCoreApplication>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QThread>

#include <cstdio>

namespace {
void reply(const QJsonObject &request, bool ok, const QString &code,
           const QJsonObject &values = {}) {
  QJsonObject response = values;
  response["requestId"] = request.value("requestId");
  response["epoch"] = request.value("epoch");
  response["ok"] = ok;
  response["code"] = code;
  const QByteArray output =
      QJsonDocument(response).toJson(QJsonDocument::Compact) + '\n';
  (void)std::fwrite(output.constData(), 1, size_t(output.size()), stdout);
  (void)std::fflush(stdout);
}
}

int main(int argc, char **argv) {
  QCoreApplication application(argc, argv);
  const QString mode = application.arguments().value(1);
  QFile input;
  if (!input.open(stdin, QIODevice::ReadOnly))
    return 2;
  quint64 frequency = 14'074'000;
  QString radioMode = "DATA";
  int filter = 3'000;
  while (true) {
    const QByteArray line = input.readLine(64 * 1024 + 1);
    if (line.isEmpty())
      break;
    QJsonParseError parse;
    const QJsonObject request =
        QJsonDocument::fromJson(line, &parse).object();
    if (parse.error != QJsonParseError::NoError)
      return 3;
    const QString operation = request.value("operation").toString();
    if (operation == "close")
      return 0;
    if (operation == "open") {
      if (mode == "slow-open")
        QThread::msleep(2'200);
      reply(request, true, "OPENED",
            {{"model", "Fixture"},
             {"manufacturer", "ShackCQ"},
             {"frequencyRangesHz", QJsonArray{QJsonObject{{"min", 1'800'000},
                                                           {"max", 54'000'000}}}},
             {"modes", QJsonArray{"CW", "USB", "LSB", "AM", "FM", "DATA"}},
             {"filtersHz", QJsonArray{200, 500, 2'400, 3'000}},
             {"setters", QJsonArray{"radio.set.frequency", "radio.set.mode",
                                     "radio.set.filter", "preset.recall"}},
             {"meters", QJsonArray{"signal", "swr", "alc"}},
             {"pttSupported", true}});
    } else if (operation == "stop" || operation == "ptt") {
      const bool verified = mode != "rx-fail";
      reply(request, verified, verified ? "PTT_READBACK_CONFIRMED"
                                        : "PTT_READBACK_UNCONFIRMED",
            {{"transmitting", operation == "ptt" &&
                                  request.value("parameters").toObject()
                                      .value("enabled").toBool(false)
                              ? true : !verified}});
      if (mode == "crash-after-key" && operation == "ptt" &&
          request.value("parameters").toObject().value("enabled").toBool())
        return 9;
    } else if (operation == "snapshot") {
      if (mode == "slow-snapshot")
        QThread::msleep(80);
      QJsonObject observed{{"frequencyHz", QJsonValue(double(frequency))},
                           {"mode", radioMode},
                           {"filterHz", filter},
                           {"transmitting", false}};
      if (request.value("parameters").toObject().value("full").toBool(true))
        observed.insert("meters", QJsonObject{{"signal", -73}});
      reply(request, true, "OBSERVED", observed);
    } else if (operation == "mutate") {
      if (mode == "block")
        QThread::msleep(10'000);
      else if (mode == "late")
        QThread::msleep(80);
      const QJsonObject envelope = request.value("parameters").toObject();
      const QString action = envelope.value("action").toString();
      const QJsonObject values = envelope.value("parameters").toObject();
      if (action == "radio.set.frequency")
        frequency = values.value("frequencyHz").toVariant().toULongLong();
      else if (action == "radio.set.mode")
        radioMode = values.value("mode").toString();
      else if (action == "radio.set.filter")
        filter = values.value("filterHz").toInt();
      QJsonObject readback;
      if (action == "radio.set.frequency")
        readback.insert("frequencyHz", QJsonValue(double(frequency)));
      else if (action == "radio.set.mode" || action == "radio.set.filter") {
        readback.insert("mode", radioMode);
        readback.insert("filterHz", filter);
      }
      reply(request, !readback.isEmpty(),
            readback.isEmpty() ? "MUTATION_READBACK_FAILED"
                               : "MUTATION_OBSERVED",
            readback);
    }
  }
  return 0;
}
