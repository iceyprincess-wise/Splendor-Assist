package com.assistant.adapter.smartassist

data class OpenSpaceCell(
    val column:Int,
    val row:Int,
    val centerX:Float,
    val centerY:Float,
    val occupancy:Int,
    val pressure:Float,
    val openness:Float,
    val viable:Boolean
)

data class OpenSpaceDetectionResult(
    val cells:List<OpenSpaceCell> = emptyList()
)

object OpenSpaceDetectionEngine {

    fun analyze(
        occupancy:SpaceOccupancyResult,
        pressure:PressureFieldResult,
        frameWidth:Float,
        frameHeight:Float
    ):OpenSpaceDetectionResult{

        if(
            occupancy.columns<=0||
            occupancy.rows<=0||
            pressure.columns<=0||
            pressure.rows<=0
        ){
            return OpenSpaceDetectionResult()
        }

        val result=ArrayList<OpenSpaceCell>()

        for(row in 0 until occupancy.rows){
            for(col in 0 until occupancy.columns){

                val occ=occupancy.occupancy[row][col]
                val p=pressure.pressure[row][col]

                val openness=
                    ((1f-p)*(1f/(1f+occ)))
                        .coerceIn(0f,1f)

                result+=OpenSpaceCell(
                    column=col,
                    row=row,
                    centerX=((col+0.5f)*frameWidth/occupancy.columns),
                    centerY=((row+0.5f)*frameHeight/occupancy.rows),
                    occupancy=occ,
                    pressure=p,
                    openness=openness,
                    viable=occ==0 && p<0.40f && openness>=0.60f
                )
            }
        }

        return OpenSpaceDetectionResult(
            result.sortedByDescending{it.openness}
        )
    }
}
