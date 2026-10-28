# Jarvis sur la montre Huawei (Watch GT 4)

La Watch GT 4 tourne sous HarmonyOS « lite » : elle n'installe pas d'APK. Ce dossier contient une petite appli pour la montre
(JavaScript, modèle « Lite Wearable » de DevEco Studio) qui parle à Jarvis sur le téléphone par **Wear Engine**, le service de Huawei
qui passe par l'appli **Huawei Santé**.

Sur la montre : six boutons (Ma journée, Météo, Prochain rappel, Mes messages, Ma voiture, Carburant). On touche, Jarvis sur le
téléphone répond avec tous ses outils, et la réponse s'affiche au poignet (on la fait défiler avec la couronne ou le doigt).
Pas de voix : une appli tierce n'a pas accès au micro de la GT 4.

Côté téléphone, c'est `app/src/main/java/com/jarvis/android/wearable/` (réglage : Paramètres > Téléphone > Montre Huawei).

## Ce qu'il faut faire une fois

Huawei ne laisse deux applis se parler que si elles sont enregistrées chez lui. Comptez quelques jours d'attente pour la validation.

1. **Compte développeur Huawei** : créez-le sur [developer.huawei.com](https://developer.huawei.com/consumer/fr/) et faites la
   vérification d'identité (compte individuel, gratuit).
2. **Jarvis dans AppGallery Connect** : dans la console, créez un projet, ajoutez-y une appli Android avec le nom de paquet
   `com.jarvis.android` (et `com.jarvis.android.dev` si vous voulez aussi la version de test). Ajoutez l'empreinte SHA-256 du
   certificat de Jarvis : elle est affichée dans Jarvis, Paramètres > Téléphone > Montre Huawei (bouton « Copier l'empreinte du
   téléphone »). Notez l'**App ID** de l'appli et ajoutez dans `local.properties` du projet, avant de construire l'APK :
   ```
   huawei.appId=123456789
   ```
3. **Demander Wear Engine** : dans la console développeur, page du service Wear Engine, « Demander » le service pour cette appli
   (autorisation de gérer les appareils et d'envoyer des messages à l'appli montre). Attendez l'accord de Huawei.
4. **DevEco Studio** sur le PC, dans une version qui propose encore le modèle « Lite Wearable » en JavaScript (la 3.1.x pour les
   montres GT ; les versions récentes pour HarmonyOS NEXT ne l'ont plus). Créez un projet **Lite Wearable > Empty Ability (JS)**
   avec le nom d'appli `com.jarvis.watch`, puis remplacez son dossier `entry/src/main/js/default` par celui d'ici.
   `entry/src/main/config.json` sert de modèle si DevEco en génère un différent : gardez surtout `bundleName`, `deviceType`
   `liteWearable` et la page `pages/index/index`.
5. **La bibliothèque Wear Engine de la montre** : téléchargez le SDK Wear Engine pour montre « lite » sur la page Wear Engine du
   site développeur Huawei et copiez son fichier `wearengine.js` dans `entry/src/main/js/default/common/`. Il n'est pas dans le dépôt
   (c'est le code de Huawei, sous sa licence).
6. **Relier les deux** :
   - dans `common/jarvis-config.js`, mettez le nom du téléphone (`com.jarvis.android`) et son empreinte (Paramètres > Téléphone >
     Montre Huawei, « À mettre dans l'appli montre ») ;
   - dans AppGallery Connect, créez aussi l'appli montre, son certificat et son profil de débogage (avec l'identifiant de votre
     montre) ; DevEco Studio guide pas à pas pour signer et installer sur la montre réelle ;
   - collez l'empreinte de l'appli montre (celle de son certificat de signature, voir la doc Wear Engine « empreinte de l'appli
     montre ») dans Jarvis, Paramètres > Téléphone > Montre Huawei, puis « Enregistrer ».
7. Dans Jarvis : activez « Relier la montre », touchez « Autoriser dans Huawei Santé » et acceptez. La ligne d'état doit dire
   « Reliée à HUAWEI WATCH GT 4… ». Ouvrez Jarvis sur la montre : « Téléphone relié » apparaît sous le titre.

## Comment ça marche

Les messages font moins de 1 Ko (limite de Wear Engine) :

| Sens | Message | Sens du message |
|---|---|---|
| montre → téléphone | `HELLO` | l'appli montre vient de s'ouvrir ; le téléphone répond `OK` |
| montre → téléphone | `P:<bouton>` | un bouton (`day`, `weather`, `reminder`, `messages`, `car`, `fuel`) |
| téléphone → montre | `W` | Jarvis réfléchit |
| téléphone → montre | `A:<i>/<n>:<texte>` | morceau i sur n de la réponse (600 caractères au plus, sans Markdown) |

Jarvis doit tourner sur le téléphone (en arrière-plan, comme pour « Hey Jarvis »). Si la montre ne répond pas : vérifiez que Huawei
Santé voit la montre, que le service Wear Engine est accordé, et que les noms et empreintes sont les mêmes des deux côtés (le message
« Scope unauthorized » de Huawei veut dire que l'un d'eux ne correspond pas).
