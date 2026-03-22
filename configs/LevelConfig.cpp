//
//  LevelConfig.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#include "LevelConfig.hpp"
#include"cocos2d.h"
#include"external/json/rapidjson.h"
#include"external/json/document.h"
USING_NS_CC;
using namespace rapidjson;
LevelConfigData LevelConfig::loadLevel(int levelID){
    LevelConfigData data;
    std::string filename="level_"+std::to_string(levelID)+".json";
    auto fileUtils = cocos2d::FileUtils::getInstance();
    std::string jsonContent=fileUtils->getStringFromFile(filename);
    if(jsonContent.empty()){
        cocos2d::log("Failed to load level config: %s", filename.c_str());
        return data;
    }
    data=praseFromJson(jsonContent);
    return data;
}
LevelConfigData LevelConfig::praseFromJson(const std::string &jsonContent){
    LevelConfigData data;
    Document doc;
        doc.Parse(jsonContent.c_str());
        
        if (doc.HasParseError()) {
            cocos2d::log("JSON parse error");
            return data;
        }
        
        // 解析 Playfield
        if (doc.HasMember("Playfield") && doc["Playfield"].IsArray()) {
            const auto& playfieldArray = doc["Playfield"];
            for (SizeType i = 0; i < playfieldArray.Size(); ++i) {
                const auto& item = playfieldArray[i];
                
                CardInitData cardData;
                cardData.cardFace = item["CardFace"].GetInt();
                cardData.cardSuit = item["CardSuit"].GetInt();
                
                if (item.HasMember("Position")) {
                    const auto& pos = item["Position"];
                    cardData.position.x = pos["x"].GetFloat();
                    cardData.position.y = pos["y"].GetFloat()+580;
                }
                
                data.playfield.push_back(cardData);
            }
        }
        
        // 解析 Stack
        if (doc.HasMember("Stack") && doc["Stack"].IsArray()) {
            const auto& stackArray = doc["Stack"];
            for (SizeType i = 0; i < stackArray.Size(); ++i) {
                const auto& item = stackArray[i];
                
                CardInitData cardData;
                cardData.cardFace = item["CardFace"].GetInt();
                cardData.cardSuit = item["CardSuit"].GetInt();
                //log("解析手牌%d成功",cardData.cardFace);
                //cardData.position = cocos2d::Vec2::ZERO;  // 牌堆里的牌位置初始为 (0,0)
                if (item.HasMember("Position")) {
                    const auto& pos = item["Position"];
                    cardData.position.x = pos["x"].GetFloat();
                    cardData.position.y = pos["y"].GetFloat();
                }
                
                data.stack.push_back(cardData);
            }
        }
        
        return data;
}
