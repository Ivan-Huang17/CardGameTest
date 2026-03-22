//
//  CardView.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#ifndef CardView_hpp
#define CardView_hpp

#include <stdio.h>
#include"cocos2d.h"
#include"CardModel.hpp"
USING_NS_CC;
class CardView:public cocos2d::Node{
public:
    static CardView* create(CardModel *model);
    bool init(CardModel* model);
    void setFaceUp(bool up);
    void setTouchCallback(std::function<void(CardView*)> callback);
    // 触摸事件处理
    bool onTouchBegan(cocos2d::Touch* touch, cocos2d::Event* event);
    void onTouchEnded(cocos2d::Touch* touch, cocos2d::Event* event);
    CardModel* _model;
private:
    
    cocos2d::Sprite *_blankSprite;
    cocos2d::Sprite *_suitSprite;
    cocos2d::Sprite *_smallNumberSprite;
    cocos2d::Sprite *_largeNumberSprite;
    void _loadCardImages();
    std::function<void(CardView*)> _touchCallback;
    bool _isTouchEnabled;
};
#endif /* CardView_hpp */
