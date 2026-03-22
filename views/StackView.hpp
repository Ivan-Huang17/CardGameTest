//
//  StackView.hpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/21.
//

#ifndef StackView_hpp
#define StackView_hpp

#include <stdio.h>
#include"cocos2d.h"
class StackView:public cocos2d::Node{
public:
    bool init();
    CREATE_FUNC(StackView);
    void setContentSize(const cocos2d::Size&size);
    cocos2d::Vec2 getOrigin()const;
private:
    cocos2d::Size _contentSize;
    
};

#endif /* StackView_hpp */
