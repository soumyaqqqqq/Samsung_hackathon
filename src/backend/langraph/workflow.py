builder.add_node("decision", decision_node)
builder.add_node("wellbeing", wellbeing_node)
builder.add_node("validation", validation_node)

builder.add_edge(START,"decision")
builder.add_edge("decision","wellbeing")
builder.add_edge("wellbeing","validation")
builder.add_edge("validation",END)