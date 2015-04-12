package kclient.module.bingo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kclient.knuddels.GroupChat;
import kclient.knuddels.network.generic.GenericProtocol;
import kclient.module.bingo.tools.BingoField;
import kclient.module.bingo.tools.BingoFieldState;
import kclient.module.bingo.tools.BingoSheetState;
import kclient.tools.Util;

/**
 *
 * @author SeBi
 */
public class BingoSheet {
    private final BingoProcess process;
    private final long sheetId;
    private final byte matrixSize;
    private final Map<Integer, BingoField> fields;
    private BingoSheetState state;
    private int bingoRound;
    private boolean oneToBingo;
    private final List<Integer> markFields;
    private boolean bingoCalled;
    private final GroupChat groupChat;
    
    public BingoSheet(GroupChat groupChat, BingoProcess process, long sheetId, GenericProtocol sheet) {
        this.process = process;
        this.sheetId = sheetId;
        this.fields = new HashMap<>();
        this.groupChat = groupChat;
        this.markFields = new ArrayList<>();
        this.matrixSize = sheet.get("BINGO_SHEET_MATRIX_SIZE");
        this.state = BingoSheetState.parse((byte)sheet.get("BINGO_SHEET_STATE_CONST"));
        
        ArrayList numbers = sheet.get("BINGO_FIELD");
        ArrayList states = sheet.get("BINGO_FIELD_STATES");
        for (int i = 0; i < numbers.size(); i++)
            this.fields.put(i, new BingoField(i, (short)numbers.get(i), (byte)states.get(i)));
    }

    public void handleUpdate(GenericProtocol sheetUpdate) {
        if (this.state != BingoSheetState.ACTIVE)
            return;
        ArrayList fieldUpdate = sheetUpdate.get("BINGO_FIELD_UPDATE");
        for (Object fu : fieldUpdate) {
            GenericProtocol field = (GenericProtocol)fu;
            int index = field.get("INDEX");
            short number = field.get("BINGO_FIELD");
            this.fields.remove(index);
            byte fstate = (byte)field.get("BINGO_FIELD_STATES");
            this.fields.put(index, new BingoField(index, number, fstate));
            if (fstate == 2 || fstate == 3)
                this.markFields.add(index);
            //System.out.println("[Sheet: " + this.sheetId + "] FieldUpdate -> " + index + ", " + getField(index).getNumber() + ", " + getField(index).getState());
        }
        //game message
        ArrayList tmpMessages = sheetUpdate.get("BINGO_GAME_MESSAGE");
        for (Object tm : tmpMessages) {
            GenericProtocol msg = (GenericProtocol)tm;
            int msgId = msg.get("MES_ID");
            String msgText = msg.get("TEXT");
            
            //System.out.println("[Sheet: " + this.sheetId + "] Message -> " + msgId + " = " + msgText);
            if (msgText.contains("noch ein Feld"))
                this.oneToBingo = true;
        }
        if (this.oneToBingo) {
            if (checkForBingo())
                this.callBingo();
        }
    }
    public void handleHistoryUpdate(GenericProtocol update) {
        if (this.state != BingoSheetState.ACTIVE)
            return;
        
        this.bingoRound = update.get("BINGO_ROUND");
        if (this.bingoRound >= 100)
            this.callBingo();
        String bingoCalledNumber = update.get("BINGO_CALLED_NUMBER");
        int length = getSize();
        if (bingoCalledNumber.contains("bonus")) {
            for (int i = 0; i < length; i++) {
                int number = this.getJokerIndex();
                if (number != -1) {
                    BingoField[] index = null;
                    for (int j = 0; j < length; j++) {
                        index = this.getFieldsByNumber(number);
                        if ((index[0] != null && index[1] != null) && 
                                (!this.markFields.contains(index[0].getIndex())) && 
                                (!this.markFields.contains(index[1].getIndex()))) {
                            break;
                        }
                    }
                    if (index == null || index[0] == null || index[1] == null)
                        continue;
                    int bestIndex = getBestIndex(index);
                    //System.out.println("[Sheet: " + this.sheetId + "] Joker Fields: Best: " + bestIndex + " | [0]" + index[0] + "; [1]" + index[1]);
                    if (this.markField(index[bestIndex]))
                        break;
                }
            }
        } else {
            int number = Integer.parseInt(bingoCalledNumber);
            for (int i = 0; i < length; i++) {
                BingoField[] index = null;
                for (int j = 0; j < length; j++) {
                    index = this.getFieldsByNumber(number);
                    if ((index[0] != null && index[1] != null) && 
                            ((!this.markFields.contains(index[0].getIndex())) && 
                            (!this.markFields.contains(index[1].getIndex())))) {
                        break;
                    }
                }
                if (index == null || index[0] == null || index[1] == null)
                    continue;
                int bestIndex = this.getBestIndex(index);
                //System.out.println("[Sheet: " + this.sheetId + "] Available Fields: BestIndex: " + bestIndex + " | [0]" + index[0] + ", [1]" + index[1]);
                if (this.markField(index[bestIndex]))
                    break;
            }
        }
    }
    
    public long getId() {
        return this.sheetId;
    }
    public boolean getBingoCalled() {
        return this.bingoCalled;
    }
    public BingoSheetState getState() {
        return this.state;
    }
    public void setState(BingoSheetState state) {
        this.state = state;
    }
    
    private void callBingo() {
        if (this.state != BingoSheetState.ACTIVE)
            return;
        this.bingoCalled = true;
        this.groupChat.sendPublicDelay(this.process.getChannel(), String.format("/bingo bingo %s", this.sheetId), Util.rnd(200, 300));
    }
    private boolean markField(BingoField field) {
        if (this.state != BingoSheetState.ACTIVE)
            return true;
        if (this.markFields.contains(field.getIndex()))
            return false;
        this.groupChat.sendPublicDelay(this.process.getChannel(), String.format("/bingo mark %s %s", this.sheetId, field.getIndex()), Util.rnd(1000, 3000));
        return true;
    }
    
    private int getBestIndex(BingoField[] field) {
        int index1 = field[0].getIndex();
        int index2 = field[1].getIndex();
        
        if (getField(index1 - 1) != null && getField(index1 - 1).getState() == BingoFieldState.SELECTED)
            return 0;
        if (getField(index1 + 1) != null && getField(index1 + 1).getState() == BingoFieldState.SELECTED)
            return 0;
        if (getField(index1 + this.matrixSize + 1) != null && getField(index1 + this.matrixSize + 1).getState() == BingoFieldState.SELECTED)
            return 0;
        if (getField(index1 - this.matrixSize - 1) != null && getField(index1 - this.matrixSize - 1).getState() == BingoFieldState.SELECTED)
            return 0;
        
        if (getField(index2 - 1) != null && getField(index2 - 1).getState() == BingoFieldState.SELECTED)
            return 1;
        if (getField(index2 + 1) != null && getField(index2 + 1).getState() == BingoFieldState.SELECTED)
            return 1;
        if (getField(index2 + this.matrixSize + 1) != null && getField(index2 + this.matrixSize + 1).getState() == BingoFieldState.SELECTED)
            return 1;
        if (getField(index2 - this.matrixSize - 1) != null && getField(index2 - this.matrixSize - 1).getState() == BingoFieldState.SELECTED)
            return 1;
        
        return 0;
    }
    
    public int getSize() {
        return this.fields.size();
    }
    public BingoField getField(int index) {
        if (index >= 0 && index <= getSize())
            return this.fields.get(index);
        return null;
    }
    public BingoField getFieldByNumber(int number, int index) {
        for (int i = index; i < this.getSize(); i++) {
            if (getField(i).getNumber() == number)
                return getField(i);
        }
        return null;
    }
    public BingoField[] getFieldsByNumber(int number) {
        BingoField[] f = new BingoField[2];
        for (int i = 0; i < this.getSize(); i++) {
            if (getField(i) != null && getField(i).getNumber() == number)
                f[f[0] == null ? 0 : 1] = getField(i);
        }
        return f;
    }
    
    private int getJokerIndex() {
        int index = getHorizontalJokerIndex();
        
        if (index == -1) {
            index = getVerticalJokerIndex();
        }
        
        if (index == -1) {
            index = getDiagonalJokerIndex();
        }
        
        if (index == -1) {
            index = getRandomJokerIndex();
        }
        
        return getField(index).getNumber();
    }
    private int getHorizontalJokerIndex() {
        for (int i = 0; i < getSize(); i++) {
            BingoField field = getField(i);
            if (field.getState() == BingoFieldState.SELECTED) {
                try {
                    if (getField(i + 1).getState() == BingoFieldState.NOT_SELECTED && getField(i + 2).getState() == BingoFieldState.SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.SELECTED && getField(i + 4).getState() == BingoFieldState.SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.SELECTED && getField(i + 6).getState() == BingoFieldState.SELECTED){

                        return i + 1;
                    } else if (getField(i + 1).getState() == BingoFieldState.SELECTED && getField(i + 2).getState() == BingoFieldState.NOT_SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.SELECTED && getField(i + 4).getState() == BingoFieldState.SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.SELECTED && getField(i + 6).getState() == BingoFieldState.SELECTED){

                        return i + 2;
                    } else if (getField(i + 1).getState() == BingoFieldState.SELECTED && getField(i + 2).getState() == BingoFieldState.SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.NOT_SELECTED && getField(i + 4).getState() == BingoFieldState.SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.SELECTED && getField(i + 6).getState() == BingoFieldState.SELECTED){

                        return i + 3;
                    } else if (getField(i + 1).getState() == BingoFieldState.SELECTED && getField(i + 2).getState() == BingoFieldState.SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.SELECTED && getField(i + 4).getState() == BingoFieldState.NOT_SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.SELECTED && getField(i + 6).getState() == BingoFieldState.SELECTED){

                        return i + 4;
                    } else if (getField(i + 1).getState() == BingoFieldState.SELECTED && getField(i + 2).getState() == BingoFieldState.SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.SELECTED && getField(i + 4).getState() == BingoFieldState.SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.NOT_SELECTED && getField(i + 6).getState() == BingoFieldState.SELECTED){

                        return i + 5;
                    } else if (getField(i + 1).getState() == BingoFieldState.SELECTED && getField(i + 2).getState() == BingoFieldState.SELECTED &&  
                        getField(i + 3).getState() == BingoFieldState.SELECTED && getField(i + 4).getState() == BingoFieldState.SELECTED &&
                        getField(i + 5).getState() == BingoFieldState.SELECTED && getField(i + 6).getState() == BingoFieldState.NOT_SELECTED){

                        return i + 6;
                    }
                } catch (Exception e) {
                }
            }
        }
        return -1;
    }
    private int getVerticalJokerIndex() {
        for (int i = 0; i < getSize(); i += this.matrixSize) {
            BingoField field = getField(i);
            if (field.getState() == BingoFieldState.SELECTED) {
                try {
                    int tmp = i + this.matrixSize - 1;
                    if (getField(tmp + 1).getState() == BingoFieldState.NOT_SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED) {

                        return tmp + 1;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.NOT_SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED) {

                        return tmp + 2;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.NOT_SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED) {

                        return tmp + 3;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.NOT_SELECTED) {

                        return tmp + 4;
                    }
                } catch (Exception e) {
                }
            }
        }
        return -1;
    }
    private int getDiagonalJokerIndex() {
        for (int i = 0; i < getSize(); i += this.matrixSize + 1) {
            BingoField field = getField(i);
            if (field.getState() == BingoFieldState.SELECTED) {
                try {
                    int tmp = i + this.matrixSize + 1;
                    if (getField(tmp + 1).getState() == BingoFieldState.NOT_SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.SELECTED && getField(tmp + 6).getState() == BingoFieldState.SELECTED) {

                        return tmp + 1;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.NOT_SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.SELECTED && getField(tmp + 6).getState() == BingoFieldState.SELECTED) {

                        return tmp + 2;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.NOT_SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.SELECTED && getField(tmp + 6).getState() == BingoFieldState.SELECTED) {

                        return tmp + 3;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.NOT_SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.SELECTED && getField(tmp + 6).getState() == BingoFieldState.SELECTED) {

                        return tmp + 4;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.NOT_SELECTED && getField(tmp + 6).getState() == BingoFieldState.SELECTED) {

                        return tmp + 5;
                    } else if (getField(tmp + 1).getState() == BingoFieldState.SELECTED && getField(tmp + 2).getState() == BingoFieldState.SELECTED &&
                            getField(tmp + 3).getState() == BingoFieldState.SELECTED && getField(tmp + 4).getState() == BingoFieldState.SELECTED && 
                            getField(tmp + 5).getState() == BingoFieldState.SELECTED && getField(tmp + 6).getState() == BingoFieldState.NOT_SELECTED) {

                        return tmp + 6;
                    }
                } catch (Exception e) {
                }
            }
        }
        return -1;
    }
    private int getRandomJokerIndex() {
        for (int i = 0; i < getSize(); i++) {
            if (getField(i).getState() == BingoFieldState.NOT_SELECTED)
                return i;
        }
        return -1;
    }
    
    private boolean checkForBingo() {
        boolean found = false;
        for (int i = 0; i < getSize(); i++) {
            found = checkHorizontalForward(i);
            if (!found) {
                found = checkVerticalTopBottom(i);
            }

            if (found)
                break;
        }
        return found;
    }
    private boolean checkHorizontalForward(int index) {
        int count = 0;
        for (int i = index; i < this.getSize(); i++) {
            if ((i - index) == this.matrixSize - 1)
                return false;
            if (getField(i).getState() == BingoFieldState.SELECTED) {
                count++;
                if (count >= 7)
                    return true;
            } else {
                return false;
            }
        }
        return false;
    }
    private boolean checkVerticalTopBottom(int index) {
        int count = 0;
        for (int i = index; i < this.getSize(); i += this.matrixSize) {
            if (getField(i).getState() == BingoFieldState.SELECTED) {
                count++;
                if (count >= 5)
                    return true;
            } else {
                return false;
            }
        }
        return false;
    }

}