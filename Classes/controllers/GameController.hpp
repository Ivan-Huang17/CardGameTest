//
//  GameController.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#ifndef GameController_hpp
#define GameController_hpp

#include "cocos2d.h"
#include"GameView.hpp"
#include"CardView.hpp"
#include"LevelConfig.hpp"
#include<vector>
#include<stack>
#include<unordered_map>

using namespace cocos2d;
class GameController: public cocos2d::Ref
{
public:
    virtual bool init();
    void startGame();
    CREATE_FUNC(GameController);
    bool isMatch(CardView*handCard,CardView*selectCard);
    
    
private:
    int _levelID=1;//初始化关卡ID
    GameView* _gameView;
    std::stack<CardView*> _playFieldCards;
    std::stack<CardView*> _stackCards;
    std::stack<CardView*> _handCards;
    void _loadLevel(int levelID);
    void _dealCards(std::vector<CardView*> cardView,const std::vector<CardInitData>& initPosition,cocos2d::Node* container);
    CardView* _createCardAtPosition(const Vec2& pos, const CardInitData& cardData);
    //CardView *_selectedCard;
    void _onStackCardTapped(CardView*card);
    void _onMainCardTapped(CardView*card);
    void _onUndoButtonTapped();
    CardView* _handCardView;        // 当前手牌（在手牌区的牌）
    int _handCardZOrderCounter;
    std::unordered_map<CardView*,cocos2d::Vec2> _cardPositions;
    void _moveCardToHandPosition(CardView* card);
    
};

#endif /* GameController_hpp */
