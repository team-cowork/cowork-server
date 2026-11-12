package com.cowork.preference.global.projection.broker

data class ProjectionTopicState(val topicId: String, val ranges: Map<Int, ProjectionBrokerRange>)
