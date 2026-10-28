// L'appli Jarvis de la montre : des boutons, Jarvis sur le téléphone répond, la réponse s'affiche.
// Le dialogue (identique côté téléphone, app/src/main/java/com/jarvis/android/wearable/WatchProtocol.kt) :
//   montre → téléphone  "HELLO", "P:<bouton>"
//   téléphone → montre  "OK", "W" (Jarvis réfléchit), "A:<i>/<n>:<texte>" (morceau i sur n de la réponse)
import { P2pClient, Message, Builder } from '../../common/wearengine';
import config from '../../common/jarvis-config';

// Les boutons : leur nom affiché, et l'identifiant que le téléphone connaît (watchPresets() côté téléphone).
var PRESETS = {
    day: 'Ma journée',
    weather: 'Météo',
    reminder: 'Prochain rappel',
    messages: 'Mes messages',
    car: 'Ma voiture',
    fuel: 'Carburant'
};

// Combien de lettres tiennent sur une ligne de l'écran rond, et combien de temps attendre le téléphone.
var LINE_CHARS = 20;
var WAIT_MS = 90000;

var p2pClient = new P2pClient();

// [text] coupé en lignes d'au plus [width] lettres, aux espaces.
function wrap(text, width) {
    var words = text.split(' ');
    var lines = [];
    var line = '';
    for (var i = 0; i < words.length; i++) {
        var word = words[i];
        while (word.length > width) {
            if (line.length > 0) {
                lines.push(line);
                line = '';
            }
            lines.push(word.substring(0, width));
            word = word.substring(width);
        }
        if (word.length === 0) {
            continue;
        }
        if (line.length === 0) {
            line = word;
        } else if (line.length + 1 + word.length <= width) {
            line = line + ' ' + word;
        } else {
            lines.push(line);
            line = word;
        }
    }
    if (line.length > 0) {
        lines.push(line);
    }
    return lines;
}

export default {
    data: {
        mode: 'menu',
        status: 'Connexion au téléphone…',
        asked: '',
        lines: [],
        parts: [],
        timer: 0
    },

    onInit() {
        p2pClient.setPeerPkgName(config.phonePackage);
        p2pClient.setPeerFingerPrint(config.phoneFingerprint);
        var page = this;
        p2pClient.registerReceiver({
            onSuccess: function () {
                page.send('HELLO');
            },
            onFailure: function () {
                page.status = 'Huawei Santé ne répond pas';
            },
            onReceiveMessage: function (data) {
                if (data && !data.isFileType) {
                    page.receive('' + data);
                }
            }
        });
    },

    onDestroy() {
        if (this.timer) {
            clearTimeout(this.timer);
        }
        p2pClient.unregisterReceiver({
            onSuccess: function () {},
            onFailure: function () {}
        });
    },

    send(text) {
        var page = this;
        var builder = new Builder();
        builder.setDescription(text);
        var message = new Message();
        message.builder = builder;
        p2pClient.send(message, {
            onSuccess: function () {},
            onFailure: function () {
                page.status = 'Téléphone injoignable';
                if (page.mode === 'answer') {
                    page.show('Le téléphone est injoignable. Jarvis est-il ouvert ?');
                }
            },
            onSendResult: function () {}
        });
    },

    receive(text) {
        if (text === 'OK') {
            this.status = 'Téléphone relié';
            return;
        }
        if (text === 'W') {
            this.show('Jarvis réfléchit…');
            return;
        }
        if (text.indexOf('A:') !== 0) {
            return;
        }
        // "A:<i>/<n>:<texte>"
        var rest = text.substring(2);
        var slash = rest.indexOf('/');
        var colon = rest.indexOf(':');
        if (slash < 0 || colon < slash) {
            return;
        }
        var index = parseInt(rest.substring(0, slash));
        var count = parseInt(rest.substring(slash + 1, colon));
        if (!(index >= 1 && count >= 1 && index <= count)) {
            return;
        }
        if (index === 1 || this.parts.length !== count) {
            this.parts = [];
            for (var i = 0; i < count; i++) {
                this.parts.push(null);
            }
        }
        this.parts[index - 1] = rest.substring(colon + 1);
        for (var j = 0; j < count; j++) {
            if (this.parts[j] === null) {
                return;
            }
        }
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = 0;
        }
        this.show(this.parts.join(''));
    },

    show(text) {
        this.lines = wrap(text, LINE_CHARS);
    },

    ask(id) {
        var page = this;
        this.asked = PRESETS[id];
        this.mode = 'answer';
        this.parts = [];
        this.show('Je demande à Jarvis…');
        if (this.timer) {
            clearTimeout(this.timer);
        }
        this.timer = setTimeout(function () {
            page.timer = 0;
            page.show('Pas de réponse du téléphone. Réessayez.');
        }, WAIT_MS);
        this.send('P:' + id);
    },

    askDay() {
        this.ask('day');
    },
    askWeather() {
        this.ask('weather');
    },
    askReminder() {
        this.ask('reminder');
    },
    askMessages() {
        this.ask('messages');
    },
    askCar() {
        this.ask('car');
    },
    askFuel() {
        this.ask('fuel');
    },

    back() {
        this.mode = 'menu';
    }
};
