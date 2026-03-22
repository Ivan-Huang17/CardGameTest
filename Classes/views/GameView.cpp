//
//  GameView.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#include "GameView.hpp"
#include"CardView.hpp"
#include"CardModel.hpp"
USING_NS_CC;
Scene* GameView::createScene(){
    return GameView::create();
}
//游戏场景初始化
bool GameView::init(){
    if (!Scene::init()) return false;
    //设置背景色
    auto topBg = LayerColor::create(Color4B(210, 180, 140, 255));
    topBg->setContentSize(Size(1080, 2080));
    this->addChild(topBg, -1);
    
    auto bottomBg = LayerColor::create(Color4B(128, 0, 128, 255));
    bottomBg->setContentSize(Size(1080, 580));
    this->addChild(bottomBg, -1);
    //添加回退按钮
    auto undoLabel = Label::createWithTTF("undo", "fonts/Marker Felt.ttf", 120);
    undoLabel->setColor(Color3B(255, 255, 0));
    auto undoItem = MenuItemLabel::create(undoLabel, [this](Ref* sender) {
        if (_undoCallback) {
            _undoCallback();
        }
    });
    
    // 设置位置 (850, 300)
    undoItem->setPosition(Vec2(900, 300));
    
    auto menu = Menu::create(undoItem, nullptr);
    menu->setPosition(Vec2::ZERO);
    menu->setLocalZOrder(100);
    this->addChild(menu, 10);
    return true;
        
}
//设置回退按钮回调函数
void GameView::setUndoCallBack(std::function<void()> callback){
    _undoCallback=callback;
}
