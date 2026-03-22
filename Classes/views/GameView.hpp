//
//  GameView.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#ifndef GameView_hpp
#define GameView_hpp

#include <stdio.h>
#include"cocos2d.h"
#include"PlayFieldView.hpp"
#include"StackView.hpp"
#include"CardView.hpp"

USING_NS_CC;
class GameView:public Scene{
public:
    static cocos2d::Scene* createScene();
    virtual bool init();
    CREATE_FUNC(GameView);
    void setUndoCallBack(std::function<void()>callback);
    
private:
    PlayFieldView* _playFieldView;
    StackView* _stackView;
    std::function<void()> _undoCallback;
};

#endif /* GameView_hpp */
