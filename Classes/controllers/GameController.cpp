//
//  GameController.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#include "GameController.hpp"
#include "LevelConfigLoader.hpp"
USING_NS_CC;
//游戏入口
void GameController::startGame(){
    _loadLevel(_levelID);
    
}
//初始化
bool GameController::init(){
    _gameView=GameView::create();
    _handCardZOrderCounter=0;
    _gameView->setUndoCallBack([this](){
         this->_onUndoButtonTapped();
    });
    Director::getInstance()->runWithScene(_gameView);
    return true;
}
//加载关卡信息
void GameController::_loadLevel(int levelID){
    LevelConfigData levelData = LevelConfig::loadLevel(levelID);
    log("=== Starting _loadLevel, map size before: %lu ===", _cardPositions.size());
    // 2. 先创建所有牌，放在屏幕中央
    std::vector<CardView*> tempCards1,tempCards2;
    Vec2 centerPos = Director::getInstance()->getVisibleSize() / 2;
    
    for (int i=0;i<levelData.playfield.size();i++) {
        CardView* card = _createCardAtPosition(centerPos, levelData.playfield[i]);
        log("创建 %d 成功",levelData.playfield[i].cardFace);
        card->setTouchCallback([this](CardView*card){
            return this->_onMainCardTapped(card);
        });
        _cardPositions[card]=levelData.playfield[i].position;
        log("Added playfield card %d to map, map size now: %lu", levelData.playfield[i].cardFace, _cardPositions.size());
        tempCards1.push_back(card);
        _playFieldCards.push(card);  // 先存着，动画结束后再用
        
    }
    for (int i=0;i<levelData.stack.size();i++) {
        CardView* card = _createCardAtPosition(centerPos, levelData.stack[i]);
        log("创建 %d 成功",levelData.stack[i].cardFace);
        
        card->setTouchCallback([this](CardView*card){
            return this->_onStackCardTapped(card);
        });
        _cardPositions[card]=levelData.stack[i].position;
        log("Added stack card %d to map, map size now: %lu", levelData.stack[i].cardFace, _cardPositions.size());
        tempCards2.push_back(card);
        _stackCards.push(card);  // 先存着，动画结束后再用
        
    }
    // 3. 依次播放发牌动画
    _dealCards(tempCards1,levelData.playfield,_gameView);
    _dealCards(tempCards2,levelData.stack,_gameView);
    
}
//播放入场动画
void GameController::_dealCards(std::vector<CardView *> cards, const std::vector<CardInitData> &targetPositions,cocos2d::Node* container){
    if (!container) {
        log("container is nullptr!");
        return;
    }
    for (int i = 0; i < cards.size(); i++) {
        container->addChild(cards[i]);
        auto moveTo = MoveTo::create(0.3f, targetPositions[i].position);
        auto easeOut = EaseOut::create(moveTo, 2.0f);
        auto delay = DelayTime::create(i * 0.1f);  // 每张牌间隔 0.1 秒
        auto sequence = Sequence::create(delay, easeOut, nullptr);
        cards[i]->runAction(sequence);
    }
}
//
CardView* GameController::_createCardAtPosition(const Vec2 &pos, const CardInitData &cardData){
    CardModel::CardSuitType suit = static_cast<CardModel::CardSuitType>(cardData.cardSuit);
    CardModel::CardFaceType face = static_cast<CardModel::CardFaceType>(cardData.cardFace);
    CardModel* model = new CardModel(suit, face);
    CardView* view = CardView::create(model);
    view->setPosition(pos);
    return view;
}
//点击备用牌回调函数
void GameController::_onStackCardTapped(CardView *card){
    _handCards.push(card);
    _handCardView=card;
    Vec2 targetPos = {700,300};
    card->setLocalZOrder(++_handCardZOrderCounter);
    auto moveTo = MoveTo::create(0.3f, targetPos);
    auto easeOut = EaseOut::create(moveTo, 4.0f);
    card->runAction(easeOut);
    
}
//点击主牌回调函数
void GameController::_onMainCardTapped(CardView *card){
    if (!_handCardView) return;
    
    if(isMatch(card, _handCardView)){
        cocos2d::Vec2 pos=card->getPosition();
        log("pos is%f,%f",pos.x,pos.y);
        //_cardPositions[card]=pos;
        _handCards.push(card);
        _handCardView=card;
        card->setLocalZOrder(++_handCardZOrderCounter);
        Vec2 targetPos = {700,300};
        auto moveTo = MoveTo::create(0.3f, targetPos);
        auto easeOut = EaseOut::create(moveTo, 2.0f);
        card->runAction(easeOut);
        
    }
}
//主牌与当前手牌匹配
bool GameController::isMatch(CardView *handCard, CardView *selectCard){
    int face1=handCard->_model->getFace();
    int face2=selectCard->_model->getFace();
    log("card1 is %d,card2 is %d",face1,face2);
    int dif=face1-face2;
    if(dif==1||dif==-1){
        return true;
    }
    return false;
}
//回退按钮回调函数
void GameController::_onUndoButtonTapped(){
    log("return succeed");
    if(_handCardView==nullptr){
        log("_handCards is empty, cannot undo");
        return;
    }
    if(_cardPositions.find(_handCardView)==_cardPositions.end()){
        log("can not find %d postision,mapsize is %lu",_handCardView->_model->getFace(),_cardPositions.size());
        return;
    }
    cocos2d::Vec2 returnPos=_cardPositions[_handCardView];
    auto moveTo=MoveTo::create(0.3f,returnPos);
    auto easeOut=EaseOut::create(moveTo,2.0f);
    _handCardView->runAction(easeOut);
    _handCards.pop();
    if(!_handCards.empty()){
        _handCardView=_handCards.top();
    }
}
