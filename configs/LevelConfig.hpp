//
//  LevelConfig.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#ifndef LevelConfig_hpp
#define LevelConfig_hpp

#include <stdio.h>
#include"cocos2d.h"
#include<string>
#include<vector>

struct CardInitData{
    int cardFace;
    int cardSuit;
    cocos2d::Vec2 position;
};
struct LevelConfigData{
    std::vector<CardInitData> playfield;
    std::vector<CardInitData> stack;
};
class LevelConfig{
public:
    static LevelConfigData loadLevel(int levelID);
    static LevelConfigData praseFromJson(const std::string& jsonContent);
};

#endif /* LevelConfig_hpp */
