//  Created by jason on 2026/3/20.
#ifndef CardModel_hpp
#define CardModel_hpp

#include <stdio.h>
#include<string>
class CardModel{
public:
    enum CardSuitType{
        CST_NONE=-1,CST_CLUBS,CST_DIAMONDS,CST_HEARTS,CST_SPADES,CST_NUM_CARD_SUIT_TYPES
    };
    enum CardFaceType{
        CFT_NONE=-1,CFT_ACE,CFT_TWO,CFT_THREE,CFT_FOUR,CFT_FIVE,CFT_SIX,CFT_SEVEN,CFT_EIGHT,CFT_NINE,CFT_TEN,CFT_JACK,CFT_QUEEN,CFT_KING,CFT_NUM_CARD_FACE_TYPES
    };
    CardFaceType getFace()const;
    CardSuitType getSuit()const;
    std::string getSuitString()const;
    std::string getFaceString()const;
    CardModel(CardSuitType suit,CardFaceType face);
    bool isRed()const;
private:
    CardSuitType _suit;
    CardFaceType _face;
};
#endif /* CardModel_hpp */
