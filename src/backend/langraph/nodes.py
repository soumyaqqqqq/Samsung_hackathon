def decision_node(state):
    result = decision.process(state)
    state["task"] = result
    return state


def wellbeing_node(state):
    state["user_state"] = wellbeing.analyze(state)
    return state


def validation_node(state):
    return validator.validate(state)