//
//  StackView.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/21.
//

#include "StackView.hpp"
bool StackView::init(){
    if(!Node::init())return false;
    
    return true;
}
void StackView::setContentSize(const cocos2d::Size&size){
    _contentSize=size;
    Node::setContentSize(size);
}
cocos2d::Vec2 StackView::getOrigin()const
{
    return this->getOrigin();
}
