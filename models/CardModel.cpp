//
//  CardModel.cpp
//  PokerGame-mobile
//
//  Created by jason on 2026/3/20.
//

#include "CardModel.hpp"

CardModel::CardModel(CardSuitType suit, CardFaceType rank) : _suit(suit), _face(rank) {}
CardModel::CardFaceType CardModel::getFace()const{
    return _face;
}
std::string CardModel::getSuitString()const{
    switch(_suit){
        case CST_CLUBS:return "club";
        case CST_HEARTS:return "heart";
        case CST_SPADES:return "spade";
        case CST_DIAMONDS:return "diamond";
        default:return"";
    }
}
std::string CardModel::getFaceString()const{
    switch(_face){
        case CFT_ACE:return "A";
        case CFT_TWO:return "2";
        case CFT_THREE:return "3";
        case CFT_FOUR:return "4";
        case CFT_FIVE:return "5";
        case CFT_SIX:return "6";
        case CFT_SEVEN:return "7";
        case CFT_EIGHT:return "8";
        case CFT_NINE:return "9";
        case CFT_TEN:return "10";
        case CFT_JACK:return "J";
        case CFT_QUEEN:return "Q";
        case CFT_KING:return "K";
        default:return "";
    }
}
bool CardModel::isRed()const{
    if(_suit==CST_HEARTS||_suit==CST_DIAMONDS){
        return true;
    }
    return false;
}

