//
//  PlayFieldView.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/21.
//

#include "PlayFieldView.hpp"
bool PlayFieldView::init(){
    if(!Node::init())return false;
    
    return true;
}
void PlayFieldView::setContentSize(const cocos2d::Size& size){
    _contentSize=size;
    Node::setContentSize(size);
}
cocos2d::Vec2 PlayFieldView::getOrigin()const{
    return this->getPosition();
}
