//
//  PlayFieldView.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/21.
//

#ifndef PlayFieldView_hpp
#define PlayFieldView_hpp

#include <stdio.h>
#include "cocos2d.h"
USING_NS_CC;
class PlayFieldView:public cocos2d::Node{
public:
    bool init();
    CREATE_FUNC(PlayFieldView);
    cocos2d::Vec2 getOrigin()const;
    void setContentSize(const cocos2d::Size& size);
private:
    cocos2d::Size _contentSize;
    
};


#endif /* PlayFieldView_hpp */
