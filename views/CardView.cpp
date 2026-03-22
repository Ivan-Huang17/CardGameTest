//
//  CardView.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#include "CardView.hpp"
#include "cocos2d.h"
#include<string>
USING_NS_CC;
CardView* CardView::create(CardModel* model){
    CardView* ret=new CardView();
    if(ret&&ret->init(model)){
        ret->autorelease();
        return ret;
    }
    delete ret;
    return nullptr;
}
bool CardView::init(CardModel* model){
    if(!Node::init())return false;
    _model=model;
    _isTouchEnabled=true;
    _loadCardImages();
    if(_blankSprite){
        
        this->setContentSize(_blankSprite->getContentSize());
        log("CardView contentSize set to: %f, %f",
                    _blankSprite->getContentSize().width,
                    _blankSprite->getContentSize().height);
    }
    this->setScale(4.0);
    auto listener = cocos2d::EventListenerTouchOneByOne::create();
    listener->setSwallowTouches(true);
    listener->onTouchBegan = CC_CALLBACK_2(CardView::onTouchBegan, this);
    listener->onTouchEnded = CC_CALLBACK_2(CardView::onTouchEnded, this);
    _eventDispatcher->addEventListenerWithSceneGraphPriority(listener, this);
    
    return true;
}
void CardView::setFaceUp(bool up){
    _suitSprite->setVisible(up);
    _smallNumberSprite->setVisible(up);
    _largeNumberSprite->setVisible(up);
}
//组合完整的卡牌
void CardView::_loadCardImages(){
    _blankSprite=Sprite::create("card_general.png");
    this->addChild(_blankSprite);
    //图案位置右上角绑定
    std::string suitPath=_model->getSuitString()+".png";
    log("suitPath = %s",suitPath.c_str());
    _suitSprite=Sprite::create(suitPath);
    _suitSprite->setPosition(Vec2(12,23));
    this->addChild(_suitSprite);
    //小数字左上角绑定
    std::string smallNumberPath="small_"+std::string(_model->isRed()?"red_":"black_")+_model->getFaceString()+".png";
    _smallNumberSprite=Sprite::create(smallNumberPath);
    _smallNumberSprite->setPosition(Vec2(-12,23));
    this->addChild(_smallNumberSprite);
    //大数字中心绑定；
    std::string largeNumberPath="big_"+std::string(_model->isRed()?"red_":"black_")+_model->getFaceString()+".png";
    _largeNumberSprite=Sprite::create(largeNumberPath);
    _largeNumberSprite->setPosition(Vec2(0,-5));
    this->addChild(_largeNumberSprite);
}
//点击开始
bool CardView::onTouchBegan(cocos2d::Touch* touch, cocos2d::Event* event) {
    log("onTouchBegan called");
    log("onTouchBegan called, parent = %p", this->getParent());
    if (!_isTouchEnabled) return false;
   
    // 1. 获取触摸点（屏幕坐标）
    Vec2 touchPoint = touch->getLocation();
    
    // 2. 转换到节点坐标系
    Vec2 localPoint = this->convertToNodeSpace(touchPoint);
    
    // 3. 获取牌的大小
    Size size = this->getContentSize();
    
    Rect rect = Rect(0, 0, size.width / 2, size.height / 2);
    if (rect.containsPoint(localPoint)) {
        log("触摸点在牌内");
        return true;  // 接收触摸
    }
    return false;
}
//点击结束
void CardView::onTouchEnded(cocos2d::Touch* touch, cocos2d::Event* event) {
    log("onTouchEnded called");
    if (_touchCallback) {
        log("touchCallback succeeded");
        _touchCallback(this);  // 通知外部
    }
}
//设置卡牌回调函数
void CardView::setTouchCallback(std::function<void(CardView*)> callback){
    _touchCallback=callback;
}
