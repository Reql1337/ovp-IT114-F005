package Project;

public class RollPayload extends Payload
 {

    private int NumofSides;

    private int NumofDice;


    public RollPayload(int NumofSides, int NumofDice) 
    {
        this.NumofDice = NumofDice;
        this.NumofSides = NumofSides;
        super.setPayloadType(PayloadType.ROLL);
    }
    
    public int getNumSides() 
    {
        return NumofSides;          //ovp 11/13
    }

    public int getNumDice() 
    {
        return NumofDice;
    }

    @Override
    public String toString()
    {
        return String.format("%d rolled %d-sided dice", NumofDice, NumofSides);
    }
}
