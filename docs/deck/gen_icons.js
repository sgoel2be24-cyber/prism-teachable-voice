const React = require("react"); const RDS = require("react-dom/server"); const sharp = require("sharp");
const md = require("react-icons/md"); const fs = require("fs");
const want = {
  FFFFFF: ["MdMic","MdTouchApp","MdAccountTree","MdAutoFixHigh","MdTextFields","MdReplay","MdVerifiedUser","MdBlock","MdHelpOutline","MdBolt",
    "MdCleaningServices","MdApps","MdMemory","MdShare","MdSchedule","MdTranslate","MdPanTool","MdStorefront","MdRecordVoiceOver","MdWifiOff",
    "MdScreenLockPortrait","MdInstallMobile","MdPhoneAndroid","MdSpeed","MdCode","MdScience","MdCloudQueue","MdCheckCircle","MdLanguage",
    "MdRestaurantMenu","MdAccessibilityNew","MdFamilyRestroom","MdRepeat","MdSmartToy","MdRoute","MdTravelExplore","MdGavel","MdSearch","MdPlayCircle","MdLink"],
  "0E9F6E": ["MdCheckCircle"], "E5484D": ["MdBlock"], "6D28D9": ["MdMic","MdCheckCircle"],
};
(async () => {
  for (const [c, names] of Object.entries(want)) for (const n of names) {
    if (!md[n]) { console.log("missing", n); continue; }
    const svg = RDS.renderToStaticMarkup(React.createElement(md[n], { color: "#" + c, size: 256 }));
    await sharp(Buffer.from(svg)).resize(256, 256).png().toFile(`${__dirname}/icons/${n}_${c}.png`);
  }
  console.log("ok", fs.readdirSync(__dirname + "/icons").length);
})();
